import { describe, expect, it } from 'vitest';
import { GdbSyncClient, type DeviceCredentials, type GdbEvent, type LocalOutboxStore, type PulledEvent } from './index.js';

const credentials: DeviceCredentials = { appCode: 'GDC', tenantId: 'tenant-1', deviceId: 'device-1', token: 'device-token' };

class FakeOutbox implements LocalOutboxStore {
  events: GdbEvent[] = [
    { eventId: 'event-1', entityType: 'ORDER', entityId: 'entity-1', operation: 'CREATE', baseVersion: 0, entityVersion: 1, payloadVersion: 1, payload: { amount: 10 }, createdAt: '2026-11-01T00:00:00Z' },
    { eventId: 'event-2', entityType: 'ORDER', entityId: 'entity-2', operation: 'CREATE', baseVersion: 0, entityVersion: 1, payloadVersion: 1, payload: { amount: 20 }, createdAt: '2026-11-01T00:00:01Z' },
  ];
  cursorValue = 0;
  acknowledged: string[] = [];
  failed: string[] = [];
  applied: PulledEvent[] = [];
  async pending(): Promise<GdbEvent[]> { return this.events; }
  async acknowledge(eventIds: string[]): Promise<void> { this.acknowledged.push(...eventIds); }
  async fail(eventIds: string[]): Promise<void> { this.failed.push(...eventIds); }
  async cursor(): Promise<number> { return this.cursorValue; }
  async applyPulledAndAdvance(events: PulledEvent[], cursor: number): Promise<void> { this.applied.push(...events); this.cursorValue = cursor; }
}

function jsonResponse(body: unknown): Response { return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }); }

describe('GdbSyncClient', () => {
  it('acknowledges accepted and duplicate events and commits a pulled cursor', async () => {
    const outbox = new FakeOutbox();
    const fetcher: typeof globalThis.fetch = async (input) => {
      const url = String(input);
      if (url.includes('/sync/push')) return jsonResponse({ batchId: 'batch-1', receivedAt: '2026-11-01T00:00:00Z', completedAt: '2026-11-01T00:00:01Z', acceptedCount: 1, duplicateCount: 1, rejectedCount: 0, conflictCount: 0, results: [
        { eventId: 'event-1', status: 'ACCEPTED', code: 'APPLIED', message: '', serverSequence: 3, acknowledgedAt: '2026-11-01T00:00:01Z' },
        { eventId: 'event-2', status: 'DUPLICATE', code: 'IDEMPOTENT_REPLAY', message: '', serverSequence: 2, acknowledgedAt: '2026-11-01T00:00:01Z' },
      ] });
      return jsonResponse({ appCode: 'GDC', tenantId: 'tenant-1', previousCursor: 0, nextCursor: 4, hasMore: false, events: [{ serverSequence: 4, eventId: 'event-3', originDeviceId: 'device-2', entityType: 'ORDER', entityId: 'entity-3', operation: 'CREATE', baseVersion: 0, entityVersion: 1, payloadVersion: 1, payload: { amount: 30 }, createdAt: '2026-11-01T00:00:02Z', acceptedAt: '2026-11-01T00:00:03Z' }] });
    };
    const report = await new GdbSyncClient({ baseUrl: 'http://localhost:8095/', credentials, outbox, fetch: fetcher }).syncOnce();
    expect(report).toMatchObject({ accepted: 1, duplicates: 1, conflicts: 0, rejected: 0, downloaded: 1, cursor: 4 });
    expect(outbox.acknowledged).toEqual(['event-1', 'event-2']);
    expect(outbox.failed).toEqual([]);
    expect(outbox.applied[0]?.eventId).toBe('event-3');
  });

  it('keeps conflicts in the local failure path for domain resolution', async () => {
    const outbox = new FakeOutbox();
    const fetcher: typeof globalThis.fetch = async (input) => String(input).includes('/sync/push')
      ? jsonResponse({ batchId: 'batch-1', receivedAt: '2026-11-01T00:00:00Z', completedAt: '2026-11-01T00:00:01Z', acceptedCount: 0, duplicateCount: 0, rejectedCount: 0, conflictCount: 2, results: outbox.events.map(event => ({ eventId: event.eventId, status: 'CONFLICT', code: 'STALE_BASE_VERSION', message: 'preserved', serverSequence: null, acknowledgedAt: '2026-11-01T00:00:01Z' })) })
      : jsonResponse({ appCode: 'GDC', tenantId: 'tenant-1', previousCursor: 0, nextCursor: 0, hasMore: false, events: [] });
    const report = await new GdbSyncClient({ baseUrl: 'http://localhost:8095', credentials, outbox, fetch: fetcher }).syncOnce();
    expect(report.conflicts).toBe(2);
    expect(outbox.acknowledged).toEqual([]);
    expect(outbox.failed).toEqual(['event-1', 'event-2']);
  });

  it('returns the one-time enrollment response from GDB', async () => {
    const fetcher: typeof globalThis.fetch = async () => jsonResponse({ appId: 'app-1', tenantId: 'tenant-1', deviceId: 'device-1', tokenType: 'Bearer', deviceToken: 'raw-token', appCode: 'GDC', tenantCode: 'DEMO', tenantName: 'Demo', registeredAt: '2026-11-01T00:00:00Z' });
    const result = await GdbSyncClient.enroll('http://localhost:8095/', 'GDBE_code', 'Test tablet', 'com.example.gdc', '1.0.0', 'ANDROID', fetcher);
    expect(result.deviceToken).toBe('raw-token');
    expect(result.appCode).toBe('GDC');
  });
});
