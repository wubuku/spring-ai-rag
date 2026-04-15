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
  uuid: string;
  entryMarkdown: string;
  filesStored: number;
}

/**
 * Response from pdf-to-rag endpoint including RAG document info.
 */
export interface PdfToRagResponse {
  documentId: number;
  title: string;
  newlyCreated: boolean;
  embedStatus: string | null;
  embedMessage: string | null;
  chunksCreated: number | null;
  uuid: string;
  entryMarkdown: string;
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
   * Import a PDF file and add it to the RAG knowledge base.
   * This is a convenience endpoint that combines import + RAG document creation.
   * Optionally triggers embedding.
   * @param file PDF File
   * @param collectionId Optional collection ID to associate with
   * @param embed Whether to trigger embedding (default: false - returns immediately)
   */
  importPdfToRag: (file: File, collectionId?: number, embed: boolean = false) => {
    const formData = new FormData();
    formData.append('file', file);
    if (collectionId !== undefined) {
      formData.append('collectionId', String(collectionId));
    }
    formData.append('embed', String(embed));
    return apiClient.post<PdfToRagResponse>('/files/pdf-to-rag', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  /**
   * Trigger embedding for an already-imported PDF (Markdown already in fs_files).
   * Uses sync mode for immediate JSON response.
   * @param uuid Virtual directory UUID of the imported PDF
   * @param collectionId Optional collection ID to associate
   * @param forceReembed Whether to force re-embedding (default: false)
   */
  triggerEmbedding: (uuid: string, collectionId?: number, forceReembed: boolean = false) => {
    const params = new URLSearchParams();
    if (collectionId !== undefined) {
      params.append('collectionId', String(collectionId));
    }
    params.append('embed', 'sync');
    params.append('forceReembed', String(forceReembed));
    return apiClient.post<PdfToRagResponse>(`/files/${uuid}/embed?${params.toString()}`, {}).then(r => r.data);
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