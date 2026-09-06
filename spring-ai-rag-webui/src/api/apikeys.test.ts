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

  it('lists principals for the policy editor', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);

    await apiKeysApi.listPrincipals();

    expect(apiClient.get).toHaveBeenCalledWith('/api-keys/principals');
  });

  it('creates a key and binds revoke and rotate to the encoded key id', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);
    vi.mocked(apiClient.delete).mockResolvedValue({ data: {} } as never);

    await apiKeysApi.createKey({ name: 'ci-key' } as never);
    expect(apiClient.post).toHaveBeenCalledWith('/api-keys', { name: 'ci-key' });

    await apiKeysApi.revokeKey('key/1');
    expect(apiClient.delete).toHaveBeenCalledWith(
      '/api-keys/key%2F1',
    );

    await apiKeysApi.rotateKey('key/1');
    expect(apiClient.post).toHaveBeenCalledWith(
      '/api-keys/key%2F1/rotate',
    );
  });

  it('updates the principal policy through the encoded principal id', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} } as never);

    await apiKeysApi.updatePolicy('principal/1', { enabled: false } as never);

    expect(apiClient.put).toHaveBeenCalledWith(
      '/api-keys/principals/principal%2F1/policy',
      { enabled: false },
    );
  });
