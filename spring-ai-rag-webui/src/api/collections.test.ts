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
