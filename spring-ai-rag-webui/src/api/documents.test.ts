import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { documentsApi } from './documents';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('documentsApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists documents with collection key and title filters', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    documentsApi.list({ page: 0, size: 20, collectionKey: 'wiki', title: 'doc' });
    expect(apiClient.get).toHaveBeenCalledWith('/documents', {
      params: { page: 0, size: 20, collectionKey: 'wiki', title: 'doc' },
    });
  });

  it('mutation lifecycle actions carry the expected document revision', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    documentsApi.disable(7, 3);
    expect(apiClient.post).toHaveBeenCalledWith('/documents/7/disable', {
      expectedDocumentRevision: 3,
    });

    documentsApi.restore(7, 3);
    expect(apiClient.post).toHaveBeenCalledWith('/documents/7/restore', {
      expectedDocumentRevision: 3,
      embeddingPolicy: 'ASYNC',
    });

    documentsApi.delete(7, 4);
    expect(apiClient.delete).toHaveBeenCalledWith('/documents/7', {
      params: { expectedDocumentRevision: 4 },
    });
  });

  it('sends batch create payloads and embedding status reads', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    documentsApi.batchCreate([{ title: 't', content: 'c' }]);
    expect(apiClient.post).toHaveBeenCalledWith('/documents/batch', {
      documents: [{ title: 't', content: 'c' }],
    });

    await documentsApi.getEmbeddingStatus();
    expect(apiClient.get).toHaveBeenCalledWith('/documents/embed-vector-status');

    await documentsApi.reembedMissing(true);
    expect(apiClient.post).toHaveBeenCalledWith(
      '/documents/embed-vector-reembed',
      null,
      { params: { force: true } },
    );
  });
});
