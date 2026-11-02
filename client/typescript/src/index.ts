export type SyncOperation = 'CREATE' | 'UPDATE' | 'DELETE';

export interface GdbEvent { eventId: string; entityType: string; entityId: string; operation: SyncOperation; baseVersion: number; entityVersion: number; payloadVersion: number; payload: Record<string, unknown>; createdAt: string; }
export interface DeviceCredentials { appCode: string; tenantId: string; deviceId: string; token: string; }
export interface EnrollmentResult { appId: string; tenantId: string; deviceId: string; tokenType: 'Bearer'; deviceToken: string; appCode: string; tenantCode: string; tenantName: string; registeredAt: string; }
export interface EventResult { eventId: string; status: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED' | 'CONFLICT' | 'RETRYABLE_FAILURE'; code: string; message: string; serverSequence: number | null; acknowledgedAt: string; }
export interface PushResponse { batchId: string; receivedAt: string; completedAt: string; acceptedCount: number; duplicateCount: number; rejectedCount: number; conflictCount: number; results: EventResult[]; }
export interface PulledEvent { serverSequence: number; eventId: string; originDeviceId: string; entityType: string; entityId: string; operation: SyncOperation; baseVersion: number; entityVersion: number; payloadVersion: number; payload: Record<string, unknown>; createdAt: string; acceptedAt: string; }
export interface PullResponse { appCode: string; tenantId: string; previousCursor: number; nextCursor: number; hasMore: boolean; events: PulledEvent[]; }

export interface LocalOutboxStore {
  pending(limit: number): Promise<GdbEvent[]>;
  acknowledge(eventIds: string[]): Promise<void>;
  fail(eventIds: string[], reason: string): Promise<void>;
  cursor(): Promise<number>;
  applyPulledAndAdvance(events: PulledEvent[], cursor: number): Promise<void>;
}

export interface GdbSyncOptions { baseUrl: string; credentials: DeviceCredentials; outbox: LocalOutboxStore; batchSize?: number; pullSize?: number; fetch?: typeof globalThis.fetch; }

export class GdbSyncClient {
  private readonly fetcher: typeof globalThis.fetch;
  private active: Promise<SyncReport> | null = null;
  private readonly batchSize: number;
  private readonly pullSize: number;
  private readonly baseUrl: string;
  constructor(private readonly options: GdbSyncOptions) {
    this.fetcher = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.batchSize = Math.min(Math.max(options.batchSize ?? 100, 1), 500);
    this.pullSize = Math.min(Math.max(options.pullSize ?? 100, 1), 500);
    this.baseUrl = options.baseUrl.replace(/\/$/, '');
  }

  static async enroll(baseUrl: string, enrollmentCode: string, deviceName: string, applicationId: string, applicationVersion: string, platform: string, fetcher: typeof globalThis.fetch = globalThis.fetch.bind(globalThis)): Promise<EnrollmentResult> {
    const response = await fetcher(`${baseUrl.replace(/\/$/, '')}/api/v1/devices/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enrollmentCode, deviceName, applicationId, applicationVersion, platform }) });
    if (!response.ok) throw new Error(await readableError(response));
    return response.json() as Promise<EnrollmentResult>;
  }

  syncOnce(): Promise<SyncReport> {
    if (this.active) return this.active;
    this.active = this.run().finally(() => { this.active = null; });
    return this.active;
  }

  async uploadSnapshot(bytes: Uint8Array, metadata: { formatVersion: number; createdAt: string; recordCounts?: Record<string, number> }): Promise<unknown> {
    const body = { formatVersion: metadata.formatVersion, createdAt: metadata.createdAt, sha256: await sha256(bytes), contentLength: bytes.byteLength, payload: bytesToBase64(bytes), recordCounts: JSON.stringify(metadata.recordCounts ?? {}) };
    return this.request('/api/v1/snapshots', { method: 'POST', body: JSON.stringify(body) });
  }

  private async run(): Promise<SyncReport> {
    const pending = await this.options.outbox.pending(this.batchSize);
    let accepted = 0; let duplicates = 0; let conflicts = 0; let rejected = 0; let downloaded = 0;
    if (pending.length) {
      const result = await this.request('/api/v1/sync/push', { method: 'POST', body: JSON.stringify({ contractVersion: 1, appCode: this.options.credentials.appCode, tenantId: this.options.credentials.tenantId, deviceId: this.options.credentials.deviceId, batchId: crypto.randomUUID(), events: pending }) }) as PushResponse;
      const acknowledged = result.results.filter(item => item.status === 'ACCEPTED' || item.status === 'DUPLICATE').map(item => item.eventId);
      await this.options.outbox.acknowledge(acknowledged);
      const failed = result.results.filter(item => item.status !== 'ACCEPTED' && item.status !== 'DUPLICATE').map(item => item.eventId);
      await this.options.outbox.fail(failed, 'Server returned a non-acknowledgement; inspect sync status.');
      accepted = result.acceptedCount; duplicates = result.duplicateCount; conflicts = result.conflictCount; rejected = result.rejectedCount;
    }
    let cursor = await this.options.outbox.cursor(); let page: PullResponse;
    do {
      page = await this.request(`/api/v1/sync/pull?after=${cursor}&limit=${this.pullSize}`, { method: 'GET' }) as PullResponse;
      await this.options.outbox.applyPulledAndAdvance(page.events, page.nextCursor); downloaded += page.events.length; cursor = page.nextCursor;
    } while (page.hasMore);
    return { accepted, duplicates, conflicts, rejected, downloaded, cursor };
  }

  private async request(path: string, init: RequestInit): Promise<unknown> {
    const headers = new Headers(init.headers); headers.set('Content-Type', 'application/json'); headers.set('Authorization', `Bearer ${this.options.credentials.token}`);
    const response = await this.fetcher(`${this.baseUrl}${path}`, { ...init, headers }); if (!response.ok) throw new Error(await readableError(response));
    return response.status === 204 ? undefined : response.json();
  }
}

export interface SyncReport { accepted: number; duplicates: number; conflicts: number; rejected: number; downloaded: number; cursor: number; }
async function sha256(bytes: Uint8Array): Promise<string> { const buffer = new ArrayBuffer(bytes.byteLength); new Uint8Array(buffer).set(bytes); const digest = await crypto.subtle.digest('SHA-256', buffer); return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join(''); }
function bytesToBase64(bytes: Uint8Array): string { let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte); return btoa(binary); }
async function readableError(response: Response): Promise<string> { try { const body = await response.json() as { message?: string }; return body.message ?? `GDB request failed (${response.status}).`; } catch { return `GDB request failed (${response.status}).`; } }
