import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { metricsApi } from './metrics';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('metricsApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the overview, metrics and usage endpoints', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    await metricsApi.overview();
    await metricsApi.get();
    await metricsApi.usage();
    expect(vi.mocked(apiClient.get).mock.calls.map(call => call[0]))
      .toEqual(['/metrics/overview', '/metrics', '/usage']);
  });
});
