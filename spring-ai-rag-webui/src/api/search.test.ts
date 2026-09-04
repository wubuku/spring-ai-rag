import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { searchApi } from './search';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('searchApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends the query with hybrid and scope parameters', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    searchApi.search({
      query: 'vector search',
      useHybrid: false,
      collectionScopeMode: 'SELECTED_COLLECTIONS',
      collectionKeys: ['docs', 'wiki'],
    });

    expect(apiClient.get).toHaveBeenCalledWith('/search', {
      params: {
        query: 'vector search',
        useHybrid: false,
        collectionScopeMode: 'SELECTED_COLLECTIONS',
        collectionKeys: ['docs', 'wiki'],
      },
      paramsSerializer: { indexes: null },
    });
  });

  it('omits optional parameters when not provided', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    searchApi.search({ query: 'plain' });

    expect(apiClient.get).toHaveBeenCalledWith('/search', {
      params: { query: 'plain' },
      paramsSerializer: { indexes: null },
    });
  });
});
