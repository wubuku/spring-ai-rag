import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { evaluationApi } from './evaluation';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('evaluationApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads reports and aggregated metrics with an optional date range', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    await evaluationApi.getReport({ startDate: '2026-01-01', endDate: '2026-01-31' });
    expect(apiClient.get).toHaveBeenCalledWith('/evaluation/report', {
      params: { startDate: '2026-01-01', endDate: '2026-01-31' },
    });

    await evaluationApi.getAggregated();
    expect(vi.mocked(apiClient.get).mock.calls[1][0])
      .toBe('/evaluation/metrics/aggregated');
  });

  it('posts manual evaluation, answer quality and feedback to their endpoints', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await evaluationApi.evaluate({ query: 'q' } as never);
    expect(vi.mocked(apiClient.post).mock.calls[0][0]).toBe('/evaluation/evaluate');

    await evaluationApi.answerQuality({ query: 'q', answer: 'a' } as never);
    expect(vi.mocked(apiClient.post).mock.calls[1][0]).toBe('/evaluation/answer-quality');

    await evaluationApi.submitFeedback({ query: 'q' } as never);
    expect(vi.mocked(apiClient.post).mock.calls[2][0]).toBe('/evaluation/feedback');
  });
});

describe('evaluationApi suites, runs and feedback history', () => {
  beforeEach(() => vi.clearAllMocks());

  it('defaults pagination for history endpoints', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);

    evaluationApi.getHistory();
    expect(apiClient.get).toHaveBeenCalledWith('/evaluation/history', {
      params: { page: 0, size: 20 },
    });

    evaluationApi.getFeedbackHistory({ page: 3, size: 50 });
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/evaluation/feedback/history',
      { params: { page: 3, size: 50 } },
    ]);

    evaluationApi.listCitationTraces();
    expect(vi.mocked(apiClient.get).mock.calls[2]).toEqual([
      '/retrieval-traces',
      { params: { page: 0, size: 20 } },
    ]);
  });

  it('creates suites and suite versions with encoded keys', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await evaluationApi.createSuite({ suiteKey: 'gold/en', name: 'Gold EN' });
    expect(apiClient.post).toHaveBeenCalledWith('/evaluation/suites', {
      suiteKey: 'gold/en',
      name: 'Gold EN',
    });

    await evaluationApi.createVersion('gold/en', { cases: 3 });
    expect(vi.mocked(apiClient.post).mock.calls[1][0]).toBe(
      '/evaluation/suites/gold%2Fen/versions',
    );
    expect(vi.mocked(apiClient.post).mock.calls[1][1]).toEqual({
      definition: { cases: 3 },
    });
  });

  it('creates, fetches and compares evaluation runs', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await evaluationApi.createRun({ suiteKey: 's1', version: 2 });
    expect(apiClient.post).toHaveBeenCalledWith('/evaluation/runs', {
      suiteKey: 's1',
      version: 2,
    });

    evaluationApi.getRun('run/1');
    expect(vi.mocked(apiClient.get).mock.calls[0][0]).toBe('/evaluation/runs/run%2F1');

    evaluationApi.compareRuns('run/1', 'run/2');
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/evaluation/runs/compare',
      { params: { leftRunId: 'run/1', rightRunId: 'run/2' } },
    ]);
  });
});
