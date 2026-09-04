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
