import type { HttpClient } from '@angular/common/http';
import { beforeEach, describe, expect, it } from 'vitest';
import { ApiService } from './api.service';

describe('ApiService configuration', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to same-origin requests for centrally hosted production', () => {
    const service = new ApiService(null as unknown as HttpClient);
    expect(service.baseUrl()).toBe('');
  });

  it('normalizes the backend URL without persisting the bootstrap token', () => {
    const service = new ApiService(null as unknown as HttpClient);
    service.configure('http://127.0.0.1:8095///', 'test-admin-token');
    expect(service.baseUrl()).toBe('http://127.0.0.1:8095');
    expect(service.adminToken()).toBe('test-admin-token');
    expect(localStorage.getItem('gdb.baseUrl')).toBe('http://127.0.0.1:8095');
    expect(localStorage.getItem('gdb.adminToken')).toBeNull();
  });
});
