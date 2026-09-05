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

describe('filesApi import and embedding', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uploads PDF imports as multipart form data with optional collection', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { uuid: 'u1' } } as never);
    const file = new File(['pdf'], 'doc.pdf');

    const result = await filesApi.importPdf(file, 'papers/');

    expect(result).toEqual({ uuid: 'u1' });
    const [url, formData, config] = vi.mocked(apiClient.post).mock.calls[0];
    expect(url).toBe('/files/pdf');
    expect(formData).toBeInstanceOf(FormData);
    expect((formData as FormData).get('file')).toBe(file);
    expect((formData as FormData).get('collection')).toBe('papers/');
    expect(config).toEqual({
      headers: { 'Content-Type': 'multipart/form-data' },
    });

    await filesApi.importPdf(file);
    const [, formDataNoCollection] = vi.mocked(apiClient.post).mock.calls[1];
    expect((formDataNoCollection as FormData).has('collection')).toBe(false);
  });

  it('uploads pdf-to-rag with collection key, embed flag and deprecated id', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { documentId: 3 } } as never);
    const file = new File(['pdf'], 'doc.pdf');

    const result = await filesApi.importPdfToRag(file, 9, true, 'papers-key');

    expect(result).toEqual({ documentId: 3 });
    const [url, formData] = vi.mocked(apiClient.post).mock.calls[0];
    expect(url).toBe('/files/pdf-to-rag');
    expect((formData as FormData).get('file')).toBe(file);
    expect((formData as FormData).get('collectionId')).toBe('9');
    expect((formData as FormData).get('collectionKey')).toBe('papers-key');
    expect((formData as FormData).get('embed')).toBe('true');

    await filesApi.importPdfToRag(file);
    const [, minimal] = vi.mocked(apiClient.post).mock.calls[1];
    expect((minimal as FormData).has('collectionId')).toBe(false);
    expect((minimal as FormData).has('collectionKey')).toBe(false);
    expect((minimal as FormData).get('embed')).toBe('false');
  });

  it('triggers sync embedding with query-string parameters', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as never);

    await filesApi.triggerEmbedding('u1', 9, true, 'papers-key');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/files/u1/embed?collectionId=9&collectionKey=papers-key&embed=sync&forceReembed=true',
      {},
    );

    await filesApi.triggerEmbedding('u1');

    expect(vi.mocked(apiClient.post).mock.calls[1][0]).toBe(
      '/files/u1/embed?embed=sync&forceReembed=false',
    );
  });

  it('unwraps blob and text payloads for raw and preview reads', async () => {
    const blob = new Blob(['raw']);
    vi.mocked(apiClient.get).mockResolvedValue({ data: blob } as never);

    await expect(filesApi.getRawFile('imports/u1/doc.pdf')).resolves.toBe(blob);
    expect(vi.mocked(apiClient.get).mock.calls[0][1]).toEqual({
      params: { path: 'imports/u1/doc.pdf' },
      responseType: 'blob',
    });

    vi.mocked(apiClient.get).mockResolvedValue({ data: '<p>x</p>' } as never);
    await expect(filesApi.getPreviewHtml('imports/u1/default.md')).resolves.toBe('<p>x</p>');
    expect(vi.mocked(apiClient.get).mock.calls[1][1]).toEqual({
      params: { path: 'imports/u1/default.md' },
      responseType: 'text',
    });
  });
});
