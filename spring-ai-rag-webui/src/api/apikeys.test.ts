import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { apiKeysApi } from './apikeys';

vi.mock('./client', () => ({
  apiClient: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe('apiKeysApi staged rotation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('sends the requested overlap and stable idempotency key', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await apiKeysApi.prepareRotation(
      'rag_k/current value',
      120,
      'rotation-request-1',
    );

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api-keys/rag_k%2Fcurrent%20value/rotations',
      { overlapSeconds: 120 },
      { headers: { 'Idempotency-Key': 'rotation-request-1' } },
    );
  });

  it('uses an empty request object when the server default overlap is selected', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await apiKeysApi.prepareRotation(
      'rag_k_current',
      undefined,
      'rotation-request-default',
    );

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api-keys/rag_k_current/rotations',
      {},
      { headers: { 'Idempotency-Key': 'rotation-request-default' } },
    );
  });

  it('binds status, complete, and cancel to the stable rotation id', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await apiKeysApi.getRotation('rotation/id');
    await apiKeysApi.completeRotation('rotation/id');
    await apiKeysApi.cancelRotation('rotation/id');

    expect(apiClient.get).toHaveBeenCalledWith(
      '/api-keys/rotations/rotation%2Fid',
    );
    expect(apiClient.post).toHaveBeenNthCalledWith(
      1,
      '/api-keys/rotations/rotation%2Fid/complete',
    );
    expect(apiClient.post).toHaveBeenNthCalledWith(
      2,
      '/api-keys/rotations/rotation%2Fid/cancel',
    );
  });
});
