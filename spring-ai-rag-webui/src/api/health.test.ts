import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { healthApi } from './health';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('healthApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the health endpoint without parameters', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    await healthApi.get();
    expect(apiClient.get).toHaveBeenCalledWith('/health');
  });
});
