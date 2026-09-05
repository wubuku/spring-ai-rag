import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { abtestApi } from './abtest';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('abtestApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists experiments with optional pagination and reads one by id', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);

    abtestApi.listExperiments();
    expect(apiClient.get).toHaveBeenCalledWith('/experiments', { params: undefined });

    abtestApi.listExperiments({ page: 2, size: 10 });
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/experiments',
      { params: { page: 2, size: 10 } },
    ]);

    abtestApi.getExperiment(7);
    expect(vi.mocked(apiClient.get).mock.calls[2][0]).toBe('/experiments/7');
  });

  it('creates and updates experiments through their collection endpoints', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} } as never);

    await abtestApi.createExperiment({ experimentName: 'n', trafficSplit: { a: 1 } });
    expect(apiClient.post).toHaveBeenCalledWith('/experiments', {
      experimentName: 'n',
      trafficSplit: { a: 1 },
    });

    await abtestApi.updateExperiment(7, { description: 'd' });
    expect(apiClient.put).toHaveBeenCalledWith('/experiments/7', { description: 'd' });
  });

  it('drives lifecycle transitions on dedicated subresources', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await abtestApi.startExperiment(1);
    await abtestApi.pauseExperiment(2);
    await abtestApi.stopExperiment(3);

    expect(vi.mocked(apiClient.post).mock.calls.map(call => call[0])).toEqual([
      '/experiments/1/start',
      '/experiments/2/pause',
      '/experiments/3/stop',
    ]);
  });

  it('reads paginated results and analysis, then deletes the experiment', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    abtestApi.getResults(5, { page: 1, size: 20 });
    expect(vi.mocked(apiClient.get).mock.calls[0]).toEqual([
      '/experiments/5/results',
      { params: { page: 1, size: 20 } },
    ]);

    abtestApi.getAnalysis(5);
    expect(vi.mocked(apiClient.get).mock.calls[1][0]).toBe('/experiments/5/analysis');

    await abtestApi.deleteExperiment(5);
    expect(apiClient.delete).toHaveBeenCalledWith('/experiments/5');
  });
});
