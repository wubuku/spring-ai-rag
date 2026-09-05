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

describe('alertsApi SLO configs, silence schedules and deliveries', () => {
  beforeEach(() => vi.clearAllMocks());

  it('manages SLO configs through their CRUD endpoints', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    alertsApi.listSloConfigs();
    expect(vi.mocked(apiClient.get).mock.calls[0][0]).toBe('/alerts/slos/configs');

    await alertsApi.createSloConfig({
      sloName: 'latency', sloType: 'P95', targetValue: 500, unit: 'ms',
    });
    expect(apiClient.post).toHaveBeenCalledWith('/alerts/slos', {
      sloName: 'latency', sloType: 'P95', targetValue: 500, unit: 'ms',
    });

    alertsApi.getSloConfig('latency');
    expect(vi.mocked(apiClient.get).mock.calls[1][0]).toBe('/alerts/slos/configs/latency');

    await alertsApi.updateSloConfig('latency', { targetValue: 300, unit: 'ms' });
    expect(apiClient.put).toHaveBeenCalledWith(
      '/alerts/slos/configs/latency',
      { targetValue: 300, unit: 'ms' },
    );

    await alertsApi.deleteSloConfig('latency');
    expect(apiClient.delete).toHaveBeenCalledWith('/alerts/slos/configs/latency');
  });

  it('manages silence schedules through their CRUD endpoints', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    alertsApi.listSilenceSchedules();
    expect(vi.mocked(apiClient.get).mock.calls[0][0]).toBe('/alerts/silence-schedules');

    await alertsApi.createSilenceSchedule({
      name: 'nightly',
      silenceType: 'WINDOW',
      startTime: '2026-09-05T23:00:00Z',
      endTime: '2026-09-06T06:00:00Z',
    });
    expect(vi.mocked(apiClient.post).mock.calls[0][0]).toBe('/alerts/silence-schedules');

    alertsApi.getSilenceSchedule('nightly');
    expect(vi.mocked(apiClient.get).mock.calls[1][0]).toBe(
      '/alerts/silence-schedules/nightly',
    );

    await alertsApi.updateSilenceSchedule('nightly', { enabled: false });
    expect(apiClient.put).toHaveBeenCalledWith(
      '/alerts/silence-schedules/nightly',
      { enabled: false },
    );

    await alertsApi.deleteSilenceSchedule('nightly');
    expect(apiClient.delete).toHaveBeenCalledWith('/alerts/silence-schedules/nightly');
  });

  it('lists notification deliveries with filters and retries one by id', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    alertsApi.listNotificationDeliveries({ status: 'PENDING', limit: 10 });
    expect(apiClient.get).toHaveBeenCalledWith('/alerts/notification-deliveries', {
      params: { status: 'PENDING', limit: 10 },
    });

    await alertsApi.retryNotificationDelivery('d-1');
    expect(apiClient.post).toHaveBeenCalledWith(
      '/alerts/notification-deliveries/d-1/retry',
    );
  });
});
