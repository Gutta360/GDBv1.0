import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { Application, AuditLog, Conflict, Dashboard, Device, Snapshot, SyncSession, Tenant } from './models';

export interface AdminSession { username: string; role: string; expiresAt: string | null; }

@Injectable({ providedIn: 'root' })
export class ApiService {
  // An empty base URL keeps production browser traffic same-origin. Local development
  // uses Angular's proxy configuration; an explicit URL remains available for review
  // environments and controlled operator access.
  readonly baseUrl = signal(localStorage.getItem('gdb.baseUrl') ?? '');
  // The legacy bootstrap token is memory-only and is not persisted. Normal
  // administration uses the backend's HttpOnly session cookie.
  readonly adminToken = signal('');
  readonly session = signal<AdminSession | null>(null);
  constructor(private readonly http: HttpClient) {}
  configure(baseUrl: string, token = ''): void { const url = baseUrl.trim().replace(/\/+$/, ''); this.baseUrl.set(url); this.adminToken.set(token.trim()); localStorage.setItem('gdb.baseUrl', url); }
  private headers(): HttpHeaders { const token = this.adminToken(); return token ? new HttpHeaders({ 'X-GDB-Admin-Token': token }) : new HttpHeaders(); }
  private options(): { headers: HttpHeaders; withCredentials: boolean } { return { headers: this.headers(), withCredentials: true }; }
  private get<T>(path: string): Promise<T> { return firstValueFrom(this.http.get<T>(`${this.baseUrl()}${path}`, this.options())); }
  private post<T>(path: string, body: unknown): Promise<T> { return firstValueFrom(this.http.post<T>(`${this.baseUrl()}${path}`, body, this.options())); }
  login(username: string, password: string): Promise<AdminSession> { return this.post<AdminSession>('/api/v1/auth/admin/login', { username, password }).then(session => { this.adminToken.set(''); this.session.set(session); return session; }); }
  bootstrap(bootstrapToken: string, username: string, password: string): Promise<AdminSession> { return this.post<AdminSession>('/api/v1/auth/admin/bootstrap', { bootstrapToken, username, password }).then(session => { this.adminToken.set(''); this.session.set(session); return session; }); }
  me(): Promise<AdminSession> { return this.get<AdminSession>('/api/v1/auth/admin/me').then(session => { this.session.set(session); return session; }); }
  logout(): Promise<void> { return this.post<void>('/api/v1/auth/admin/logout', {}).then(() => { this.session.set(null); }); }
  dashboard(): Promise<Dashboard> { return this.get('/api/v1/admin/monitoring/dashboard'); }
  applications(): Promise<Application[]> { return this.get('/api/v1/admin/applications'); }
  createApplication(body: unknown): Promise<Application> { return this.post('/api/v1/admin/applications', body); }
  tenants(): Promise<Tenant[]> { return this.get('/api/v1/admin/tenants'); }
  createTenant(body: unknown): Promise<Tenant> { return this.post('/api/v1/admin/tenants', body); }
  devices(): Promise<Device[]> { return this.get('/api/v1/admin/devices'); }
  revokeDevice(id: string): Promise<unknown> { return this.post(`/api/v1/admin/devices/${id}/revoke`, {}); }
  conflicts(): Promise<Conflict[]> { return this.get('/api/v1/admin/conflicts'); }
  snapshots(): Promise<Snapshot[]> { return this.get('/api/v1/admin/snapshots'); }
  audit(): Promise<AuditLog[]> { return this.get('/api/v1/admin/audit'); }
  syncSessions(): Promise<SyncSession[]> { return this.get('/api/v1/admin/synchronization'); }
}
