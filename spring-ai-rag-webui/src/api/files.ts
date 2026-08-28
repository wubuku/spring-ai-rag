import { apiClient } from './client';

export interface TreeEntry {
  name: string;
  path: string;
  type: 'file' | 'directory';
  mimeType: string | null;
  size: number;
  createdAt?: string | null;
  displayName?: string | null;
  originalFilename?: string | null;
  importId?: string | null;
  sourceType?: string | null;
}

export interface FileImportMetadata {
  importId: string;
  sourceType: string;
  originalFilename: string;
  displayName: string;
  entryPath: string;
  originalPath: string;
  fileCount: number;
  createdAt: string;
}

export interface TreeResponse {
  path: string;
  entries: TreeEntry[];
  total: number;
  importMetadata?: FileImportMetadata | null;
}

export interface PdfImportResponse {
  uuid: string;
  entryMarkdown: string;
  filesStored: number;
  originalFilename?: string | null;
  displayName?: string | null;
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
   * @param collectionId Deprecated optional numeric Collection ID
   * @param embed Whether to trigger embedding (default: false - returns immediately)
   * @param collectionKey Preferred stable Collection key
   */
  importPdfToRag: (file: File, collectionId?: number, embed: boolean = false, collectionKey?: string) => {
    const formData = new FormData();
    formData.append('file', file);
    if (collectionId !== undefined) {
      formData.append('collectionId', String(collectionId));
    }
    if (collectionKey !== undefined) {
      formData.append('collectionKey', collectionKey);
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
   * @param collectionId Deprecated optional numeric Collection ID
   * @param forceReembed Whether to force re-embedding (default: false)
   * @param collectionKey Preferred stable Collection key
   */
  triggerEmbedding: (uuid: string, collectionId?: number, forceReembed: boolean = false, collectionKey?: string) => {
    const params = new URLSearchParams();
    if (collectionId !== undefined) {
      params.append('collectionId', String(collectionId));
    }
    if (collectionKey !== undefined) {
      params.append('collectionKey', collectionKey);
    }
    params.append('embed', 'sync');
    params.append('forceReembed', String(forceReembed));
    return apiClient.post<PdfToRagResponse>(`/files/${uuid}/embed?${params.toString()}`, {}).then(r => r.data);
  },

  getRawFile: (path: string) =>
    apiClient.get<Blob>('/files/raw', {
      params: { path },
      responseType: 'blob',
    }).then(response => response.data),

  getPreviewHtml: (path: string) =>
    apiClient.get<string>('/files/preview', {
      params: { path },
      responseType: 'text',
    }).then(response => response.data),
};
