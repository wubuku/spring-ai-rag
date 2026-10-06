import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { abtestApi } from './abtest';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

// Batch 932. These assertions pin the request the client builds. They never
// verified that the server serves it, and the one that looked closest —
// "then deletes the experiment" — was aimed at a route no handler has ever had.
// The check that answers the other half is `everyWebUiRoute_isServed` in
// OpenApiContractTest, which reads the running app's own route table. The `/ab`
// prefix below is load-bearing: it is what `AbTestController`'s
// `@RequestMapping("/rag/ab")` plus `@ApiVersion("v1")` compose to, and without
// it every call here is a 404.
describe('abtestApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists experiments with optional pagination and reads one by id', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    abtestApi.listExperiments();
    expect(apiClient.get).toHaveBeenCalledWith('/ab/experiments', { params: undefined });

    abtestApi.listExperiments({ page: 2, size: 10 });
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/ab/experiments',
      { params: { page: 2, size: 10 } },
    ]);

    abtestApi.getExperiment(7);
    expect(vi.mocked(apiClient.get).mock.calls[2][0]).toBe('/ab/experiments/7');
  });

  it('creates and updates experiments through their collection endpoints', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} } as never);

    await abtestApi.createExperiment({ experimentName: 'n', trafficSplit: { a: 1 } });
    expect(apiClient.post).toHaveBeenCalledWith('/ab/experiments', {
      experimentName: 'n',
      trafficSplit: { a: 1 },
    });

    await abtestApi.updateExperiment(7, { description: 'd' });
    expect(apiClient.put).toHaveBeenCalledWith('/ab/experiments/7', { description: 'd' });
  });

  it('drives lifecycle transitions on dedicated subresources', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await abtestApi.startExperiment(1);
    await abtestApi.pauseExperiment(2);
    await abtestApi.stopExperiment(3);

    expect(vi.mocked(apiClient.post).mock.calls.map(call => call[0])).toEqual([
      '/ab/experiments/1/start',
      '/ab/experiments/2/pause',
      '/ab/experiments/3/stop',
    ]);
  });

  it('reads paginated results and analysis', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    abtestApi.getResults(5, { page: 1, size: 20 });
    expect(vi.mocked(apiClient.get).mock.calls[0]).toEqual([
      '/ab/experiments/5/results',
      { params: { page: 1, size: 20 } },
    ]);

    abtestApi.getAnalysis(5);
    expect(vi.mocked(apiClient.get).mock.calls[1][0]).toBe('/ab/experiments/5/analysis');
  });

  it('offers no method aimed at a route the server does not serve', () => {
    // Batch 932. `deleteExperiment` was deleted rather than repointed, and this
    // is what stops the same dead capability being re-added by the next person
    // who assumes the obvious endpoint exists: the client must not grow a
    // verb+path combination that `AbTestController` has no mapping for. The
    // authoritative half of that claim is the route contract test; this half
    // keeps the mistake from looking finished.
    const callable = Object.entries(abtestApi)
      .filter(([, value]) => typeof value === 'function')
      .map(([name]) => name);

    expect(callable).not.toContain('deleteExperiment');
    expect(callable.sort()).toEqual([
      'createExperiment',
      'getAnalysis',
      'getExperiment',
      'getResults',
      'listExperiments',
      'pauseExperiment',
      'startExperiment',
      'stopExperiment',
      'updateExperiment',
    ]);
  });
});
