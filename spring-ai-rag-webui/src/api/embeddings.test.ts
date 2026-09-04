import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { embeddingsApi } from './embeddings';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('embeddingsApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists jobs with the requested filters', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    embeddingsApi.listJobs({ page: 1, size: 25, status: 'QUEUED' });
    expect(apiClient.get).toHaveBeenCalledWith('/embedding-jobs', {
      params: { page: 1, size: 25, status: 'QUEUED' },
    });
  });

  it('targets job lifecycle actions on the job resource', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    await embeddingsApi.cancelJob('job-1');
    await embeddingsApi.retryJob('job-2');
    expect(apiClient.post).toHaveBeenCalledWith('/embedding-jobs/job-1/cancel');
    expect(apiClient.post).toHaveBeenCalledWith('/embedding-jobs/job-2/retry');
  });

  it('scopes readiness queries to the requested collection key', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    await embeddingsApi.readiness('wiki');
    await embeddingsApi.derivationReadiness('wiki');
    expect(apiClient.get).toHaveBeenCalledWith(
      '/collections/embedding-readiness',
      { params: { collectionKey: 'wiki' } },
    );
    expect(vi.mocked(apiClient.get).mock.calls[1][0])
      .toBe('/collections/derivation-readiness');
  });
});
