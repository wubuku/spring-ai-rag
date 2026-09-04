import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { alertsApi } from './alerts';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('alertsApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads active alerts and history with filters', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);

    await alertsApi.listActive();
    expect(apiClient.get).toHaveBeenCalledWith('/alerts/active');

    await alertsApi.listHistory({ limit: 10 });
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/alerts/history',
      { params: { limit: 10 } },
    ]);
  });

  it('fires, resolves and silences alerts on their dedicated endpoints', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await alertsApi.fire({ alertType: 'SLO', message: 'm' } as never);
    expect(vi.mocked(apiClient.post).mock.calls[0][0]).toBe('/alerts/fire');

    await alertsApi.resolve(3, 'fixed');
    expect(vi.mocked(apiClient.post).mock.calls[1][0]).toBe('/alerts/3/resolve');

    await alertsApi.silence('k', 30);
    expect(vi.mocked(apiClient.post).mock.calls[2]).toEqual([
      '/alerts/silence',
      { alertKey: 'k', durationMinutes: 30 },
    ]);
  });
});
