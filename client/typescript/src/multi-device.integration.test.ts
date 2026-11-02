import { describe, expect, it } from 'vitest';
import { GdbSyncClient, type DeviceCredentials, type GdbEvent, type LocalOutboxStore, type PulledEvent, type PushResponse } from './index.js';

type EntityState = { version: number; payload: Record<string, unknown> };

/**
 * A test-only durable-store model. A real GApp supplies the same contract with
 * its SQLite/SQLCipher transaction; each instance deliberately owns its own
 * outbox, entity state, and cursor.
 */
class DurableTestStore implements LocalOutboxStore {
  private readonly pendingEvents: GdbEvent[] = [];
  private readonly entities = new Map<string, EntityState>();
  private readonly appliedEventIds = new Set<string>();
  private cursorValue = 0;
  readonly failed: string[] = [];

  add(event: GdbEvent): void { this.pendingEvents.push(event); }
  state(entityId: string): EntityState | undefined { return this.entities.get(entityId); }
  async pending(limit: number): Promise<GdbEvent[]> { return this.pendingEvents.slice(0, limit); }
  async acknowledge(eventIds: string[]): Promise<void> {
    const ids = new Set(eventIds);
    for (let index = this.pendingEvents.length - 1; index >= 0; index--) if (ids.has(this.pendingEvents[index].eventId)) this.pendingEvents.splice(index, 1);
  }
  async fail(eventIds: string[]): Promise<void> { this.failed.push(...eventIds); }
  async cursor(): Promise<number> { return this.cursorValue; }
  async applyPulledAndAdvance(events: PulledEvent[], cursor: number): Promise<void> {
    for (const event of events) {
      if (this.appliedEventIds.has(event.eventId)) continue;
      this.appliedEventIds.add(event.eventId);
      if (event.operation === 'DELETE') this.entities.delete(event.entityId);
      else this.entities.set(event.entityId, { version: event.entityVersion, payload: event.payload });
    }
    this.cursorValue = cursor;
  }
}

class SimulatedGdbServer {
  private sequence = 0;
  private readonly events: PulledEvent[] = [];
  private readonly eventIds = new Set<string>();
  private readonly versions = new Map<string, number>();

  fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith('/sync/push')) return this.push(JSON.parse(String(init?.body)) as { events: GdbEvent[] });
    const after = Number(new URL(String(input)).searchParams.get('after') ?? '0');
    const page = this.events.filter(event => event.serverSequence > after);
    const limit = Number(new URL(String(input)).searchParams.get('limit') ?? '100');
    const events = page.slice(0, limit);
    const nextCursor = events.at(-1)?.serverSequence ?? after;
    return json({ appCode: 'GDC', tenantId: 'tenant-1', previousCursor: after, nextCursor, hasMore: page.length > events.length, events });
  };

  private push(request: { events: GdbEvent[] }): Response {
    const results: PushResponse['results'] = [];
    for (const event of request.events) {
      if (this.eventIds.has(event.eventId)) {
        results.push({ eventId: event.eventId, status: 'DUPLICATE', code: 'IDEMPOTENT_REPLAY', message: '', serverSequence: null, acknowledgedAt: new Date().toISOString() });
        continue;
      }
      const currentVersion = this.versions.get(event.entityId) ?? 0;
      if (event.baseVersion !== currentVersion) {
        results.push({ eventId: event.eventId, status: 'CONFLICT', code: 'STALE_BASE_VERSION', message: 'preserved', serverSequence: null, acknowledgedAt: new Date().toISOString() });
        continue;
      }
      const serverSequence = ++this.sequence;
      this.eventIds.add(event.eventId);
      this.versions.set(event.entityId, event.entityVersion);
      this.events.push({ ...event, serverSequence, originDeviceId: 'origin-device', acceptedAt: new Date().toISOString() });
      results.push({ eventId: event.eventId, status: 'ACCEPTED', code: 'APPLIED', message: '', serverSequence, acknowledgedAt: new Date().toISOString() });
    }
    return json({ batchId: 'simulated-batch', receivedAt: new Date().toISOString(), completedAt: new Date().toISOString(),
      acceptedCount: results.filter(item => item.status === 'ACCEPTED').length,
      duplicateCount: results.filter(item => item.status === 'DUPLICATE').length,
      rejectedCount: results.filter(item => item.status === 'REJECTED').length,
      conflictCount: results.filter(item => item.status === 'CONFLICT').length, results });
  }
}

function json(body: unknown): Response { return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }); }
function event(deviceId: string, entityId: string, operation: GdbEvent['operation'], baseVersion: number, entityVersion: number, payload: Record<string, unknown>): GdbEvent {
  return { eventId: `${deviceId}-${entityVersion}-${crypto.randomUUID()}`, entityType: 'ORDER', entityId, operation, baseVersion, entityVersion, payloadVersion: 1, payload, createdAt: new Date().toISOString() };
}
function client(server: SimulatedGdbServer, store: DurableTestStore, deviceId: string): GdbSyncClient {
  const credentials: DeviceCredentials = { appCode: 'GDC', tenantId: 'tenant-1', deviceId, token: `${deviceId}-token` };
  return new GdbSyncClient({ baseUrl: 'http://simulated-gdb', credentials, outbox: store, fetch: server.fetch });
}

describe('simulated two-device convergence', () => {
  it('converges independent stores and cursors through the common server log', async () => {
    const server = new SimulatedGdbServer();
    const deviceA = new DurableTestStore();
    const deviceB = new DurableTestStore();
    const entityId = 'shared-entity';
    deviceA.add(event('device-a', entityId, 'CREATE', 0, 1, { name: 'initial' }));

    await client(server, deviceA, 'device-a').syncOnce();
    await client(server, deviceB, 'device-b').syncOnce();
    deviceB.add(event('device-b', entityId, 'UPDATE', 1, 2, { name: 'updated-by-b' }));
    await client(server, deviceB, 'device-b').syncOnce();
    await client(server, deviceA, 'device-a').syncOnce();

    expect(deviceA.state(entityId)).toEqual({ version: 2, payload: { name: 'updated-by-b' } });
    expect(deviceB.state(entityId)).toEqual(deviceA.state(entityId));
    expect(await deviceA.cursor()).toBe(2);
    expect(await deviceB.cursor()).toBe(2);
    expect(await deviceA.pending(10)).toEqual([]);
    expect(await deviceB.pending(10)).toEqual([]);
  });

  it('preserves a stale concurrent update for domain resolution', async () => {
    const server = new SimulatedGdbServer();
    const deviceA = new DurableTestStore();
    const deviceB = new DurableTestStore();
    const entityId = 'conflicted-entity';
    deviceA.add(event('device-a', entityId, 'CREATE', 0, 1, { value: 'one' }));
    await client(server, deviceA, 'device-a').syncOnce();
    await client(server, deviceB, 'device-b').syncOnce();
    deviceA.add(event('device-a', entityId, 'UPDATE', 1, 2, { value: 'a' }));
    deviceB.add(event('device-b', entityId, 'UPDATE', 1, 2, { value: 'b' }));
    await client(server, deviceA, 'device-a').syncOnce();
    const report = await client(server, deviceB, 'device-b').syncOnce();

    expect(report.conflicts).toBe(1);
    expect(deviceB.failed).toHaveLength(1);
    expect(deviceB.state(entityId)).toEqual({ version: 2, payload: { value: 'a' } });
    expect(await deviceB.cursor()).toBe(2);
  });
});
