import { apiClient } from './client';

export interface TreeEntry {
  name: string;
  path: string;
  type: 'file' | 'directory';
  mimeType: string | null;
  size: number;
}

export interface TreeResponse {
  path: string;
  entries: TreeEntry[];
  total: number;
}

export interface PdfImportResponse {
  virtualRoot: string;
  entryMarkdown: string;
  filesImported: number;
}

const BASE_URL = '/api/v1/rag/files';

export const filesApi = {
  /**
   * List direct children under a virtual path prefix.
   * @param path URL-encoded virtual path (e.g., "" for root, "papers/" for subdirectory)
   */
  listTree: (path?: string) =>
    apiClient.get<TreeResponse>('/files/tree', {
      params: path !== undefined ? { path } : {},
    }),

  /**
   * Import a PDF file. The PDF is converted to Markdown + images.
   * @param file PDF File
   * @param collection Optional collection/subdirectory prefix
   */
  importPdf: (file: File, collection?: string) => {
    const formData = new FormData();
    formData.append('file', file);
    if (collection) {
      formData.append('collection', collection);
    }
    return apiClient.post<PdfImportResponse>('/files/pdf', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  /**
   * Get the raw file content URL for embedding/preview.
   * @param path URL-encoded virtual file path
   */
  rawFileUrl: (path: string): string => {
    const encoded = encodeURIComponent(path);
    return `${BASE_URL}/raw?path=${encoded}`;
  },

  /**
   * Get the HTML preview URL for a PDF or Markdown file.
   * @param path URL-encoded virtual path of the PDF or Markdown file
   */
  previewUrl: (path: string): string => {
    const encoded = encodeURIComponent(path);
    return `${BASE_URL}/preview?path=${encoded}`;
  },
};
