import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { modelsApi } from './models';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('modelsApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists models from the models endpoint', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);
    await modelsApi.list();
    expect(apiClient.get).toHaveBeenCalledWith('/models');
  });
});
