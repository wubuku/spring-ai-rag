import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { authApi } from './auth';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('authApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends the credential as an X-API-Key header, never in the URL', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    await authApi.currentIdentity('secret-root-key');

    const [url, config] = vi.mocked(apiClient.get).mock.calls[0];
    expect(url).toBe('/auth/me');
    expect(url).not.toContain('secret-root-key');
    expect(config).toEqual({ headers: { 'X-API-Key': 'secret-root-key' } });
  });
});
