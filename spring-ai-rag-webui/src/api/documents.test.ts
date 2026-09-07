import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { documentsApi } from './documents';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
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

describe('documentsApi full endpoint coverage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('updates a document with its full mutation payload', async () => {
    vi.mocked(apiClient.patch).mockResolvedValue({ data: {} } as never);

    await documentsApi.update(5, {
      expectedDocumentRevision: 2,
      title: 'renamed',
      content: 'body',
      source: null,
      collectionKey: null,
      embeddingPolicy: 'ASYNC',
    } as never);

    expect(apiClient.patch).toHaveBeenCalledWith('/documents/5', {
      expectedDocumentRevision: 2,
      title: 'renamed',
      content: 'body',
      source: null,
      collectionKey: null,
      embeddingPolicy: 'ASYNC',
    });
  });

  it('embeds with the force flag and batches embedding requests', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    documentsApi.embed(3, true);
    expect(apiClient.post).toHaveBeenCalledWith(
      '/documents/3/embed',
      null,
      { params: { force: true } },
    );

    documentsApi.batchEmbed([7, 8, 9]);
    expect(apiClient.post).toHaveBeenCalledWith(
      '/documents/batch/embed',
      { documentIds: [7, 8, 9] },
    );
  });

  it('uploads and embeds with the multipart content type', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    const formData = new FormData();

    await documentsApi.uploadAndEmbed(formData);

    expect(apiClient.post).toHaveBeenCalledWith(
      '/documents/upload',
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    );
  });

  it('reads version history and single version details', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    documentsApi.getVersions(11);
    expect(apiClient.get).toHaveBeenCalledWith(
      '/documents/11/versions',
      { params: { page: 0, size: 20 } },
    );

    documentsApi.getVersions(11, 2, 10);
    expect(apiClient.get).toHaveBeenCalledWith(
      '/documents/11/versions',
      { params: { page: 2, size: 10 } },
    );

    await documentsApi.getVersion(11, 3);
    expect(apiClient.get).toHaveBeenCalledWith('/documents/11/versions/3');
  });

  it('restores a version with policy and visibility mode', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await documentsApi.restoreVersion(5, 2, 7, 'SYNC', 'SNAPSHOT');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/documents/5/versions/2/restore',
      {
        expectedDocumentRevision: 7,
        embeddingPolicy: 'SYNC',
        visibilityMode: 'SNAPSHOT',
      },
    );
  });

  it('relocates an external document with an idempotency key header', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    const request = {
      sourceCollectionKey: 'source-col',
      targetCollectionKey: 'target-col',
      sourceNamespace: 'crm',
      externalId: 'cms:1',
      expectedSourceRevision: 'etag:1',
    };

    await documentsApi.relocate(request, 'idem-1');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/documents/relocate',
      request,
      { headers: { 'Idempotency-Key': 'idem-1' } },
    );
  });
});
