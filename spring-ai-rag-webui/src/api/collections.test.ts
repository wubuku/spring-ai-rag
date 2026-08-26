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
