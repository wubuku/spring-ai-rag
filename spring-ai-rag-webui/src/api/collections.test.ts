import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { collectionsApi } from './collections';

vi.mock('./client', () => ({
  apiClient: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe('collectionsApi.create', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('generates one idempotency key for each create invocation', async () => {
    const randomUUID = vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('11111111-1111-4111-8111-111111111111')
      .mockReturnValueOnce('22222222-2222-4222-8222-222222222222');
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    const first = {
      collectionKey: 'first-collection',
      name: 'First Collection',
    };
    const second = {
      collectionKey: 'second-collection',
      name: 'Second Collection',
    };
    await collectionsApi.create(first);
    await collectionsApi.create(second);

    expect(randomUUID).toHaveBeenCalledTimes(2);
    expect(apiClient.post).toHaveBeenNthCalledWith(
      1,
      '/collections',
      first,
      {
        headers: {
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
      },
    );
    expect(apiClient.post).toHaveBeenNthCalledWith(
      2,
      '/collections',
      second,
      {
        headers: {
          'Idempotency-Key': '22222222-2222-4222-8222-222222222222',
        },
      },
    );
  });
});

describe('collectionsApi purge contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('requests caller-aware integration capabilities', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    await collectionsApi.integrationCapabilities();

    expect(apiClient.get).toHaveBeenCalledWith('/integration-capabilities');
  });

  it('creates a preview by stable Collection key', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await collectionsApi.previewPurge('tenant:catalog:v1');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/collections/by-key/purge/preview',
      undefined,
      { params: { collectionKey: 'tenant:catalog:v1' } },
    );
  });

  it('applies the complete frozen preview envelope', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    const request = {
      collectionKey: 'tenant:catalog:v1',
      previewId: '33333333-3333-4333-8333-333333333333',
      confirmationToken: 'secret-token',
      fingerprint: 'sha256-fingerprint',
      expectedCollectionVersion: 7,
      expectedChatCommitFenceVersion: 12,
    };

    await collectionsApi.applyPurge(request);

    expect(apiClient.post).toHaveBeenCalledWith(
      '/collections/by-key/purge',
      request,
    );
  });
});

describe('collectionsApi query variants and purge flow', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads capabilities, pages with offset math and drops empty queries', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    collectionsApi.integrationCapabilities();
    expect(vi.mocked(apiClient.get).mock.calls[0][0]).toBe('/integration-capabilities');

    collectionsApi.list({ page: 2, size: 5, query: 'pdf' });
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/collections',
      { params: { offset: 10, limit: 5, query: 'pdf' } },
    ]);

    collectionsApi.list();
    expect(vi.mocked(apiClient.get).mock.calls[2]).toEqual([
      '/collections',
      { params: { offset: 0, limit: 20, query: undefined } },
    ]);
  });

  it('resolves collections by numeric id and stable key', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    collectionsApi.get(4);
    expect(vi.mocked(apiClient.get).mock.calls[0][0]).toBe('/collections/4');

    collectionsApi.getByKey('k1');
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/collections/by-key',
      { params: { collectionKey: 'k1' } },
    ]);

    collectionsApi.export(4);
    expect(vi.mocked(apiClient.get).mock.calls[2][0]).toBe('/collections/4/export');
  });

  it('updates and deletes through both id and key variants', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    collectionsApi.update(4, { enabled: false });
    expect(apiClient.put).toHaveBeenCalledWith('/collections/4', { enabled: false });

    collectionsApi.updateByKey('k1', { name: 'n' });
    expect(apiClient.put).toHaveBeenCalledWith(
      '/collections/by-key',
      { name: 'n' },
      { params: { collectionKey: 'k1' } },
    );

    collectionsApi.delete(4);
    expect(apiClient.delete).toHaveBeenCalledWith('/collections/4');

    collectionsApi.deleteByKey('k1');
    expect(apiClient.delete).toHaveBeenCalledWith(
      '/collections/by-key',
      { params: { collectionKey: 'k1' } },
    );
  });

  it('runs the two-phase purge flow through preview and apply', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    const applyRequest = {
      collectionKey: 'k1',
      previewId: 'p1',
      confirmationToken: 'tok',
      fingerprint: 'fp',
      expectedCollectionVersion: 3,
      expectedChatCommitFenceVersion: 1,
    };

    collectionsApi.previewPurge('k1');
    expect(apiClient.post).toHaveBeenCalledWith(
      '/collections/by-key/purge/preview',
      undefined,
      { params: { collectionKey: 'k1' } },
    );

    await collectionsApi.applyPurge(applyRequest);
    expect(apiClient.post).toHaveBeenCalledWith('/collections/by-key/purge', applyRequest);
  });

  it('manages collection documents and import/export payloads', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    collectionsApi.addDocuments(4, [1, 2]);
    expect(apiClient.post).toHaveBeenCalledWith('/collections/4/documents', {
      documentIds: [1, 2],
    });

    collectionsApi.removeDocuments(4, [1, 2]);
    expect(apiClient.delete).toHaveBeenCalledWith('/collections/4/documents', {
      data: { documentIds: [1, 2] },
    });

    await collectionsApi.importCollection({ name: 'copy', items: [] });
    expect(apiClient.post).toHaveBeenCalledWith('/collections/import', {
      name: 'copy',
      items: [],
    });
  });
});
