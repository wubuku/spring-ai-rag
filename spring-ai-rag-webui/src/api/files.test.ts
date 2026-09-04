import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { filesApi } from './files';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('filesApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists the tree root without a path parameter and subdirectories with one', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    filesApi.listTree();
    expect(apiClient.get).toHaveBeenCalledWith('/files/tree', { params: {} });

    filesApi.listTree('papers/');
    expect(vi.mocked(apiClient.get).mock.calls[1]).toEqual([
      '/files/tree',
      { params: { path: 'papers/' } },
    ]);
  });

  it('previews and raw-downloads through the dedicated endpoints', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    await filesApi.getPreviewHtml('imports/u1/default.md');
    expect(vi.mocked(apiClient.get).mock.calls[0][0]).toBe('/files/preview');

    await filesApi.getRawFile('imports/u1/original.pdf');
    expect(vi.mocked(apiClient.get).mock.calls[1][0]).toBe('/files/raw');
  });
});
