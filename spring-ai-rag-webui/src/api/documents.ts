import { apiClient } from './client';

export interface Document {
  id: number;
  title: string;
  content: string;
  source: string;
  contentHash: string;
  documentType: string;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  collectionId: number | null;
  collectionName: string | null;
  chunkCount: number;
}

export interface DocumentListResponse {
  documents: Document[];
  total: number;
  offset: number;
  limit: number;
}

export const documentsApi = {
  list: (params: { page?: number; size?: number; collectionId?: number; title?: string }) =>
    apiClient.get<DocumentListResponse>('/documents', { params }),

  get: (id: number) => apiClient.get<Document>(`/documents/${id}`),

  delete: (id: number) => apiClient.delete(`/documents/${id}`),

  batchCreate: (
    docs: Array<{
      title: string;
      content: string;
      source?: string;
      documentType?: string;
      collectionId?: number;
    }>
  ) =>
    apiClient.post<{ documentIds: number[]; failed: number }>('/documents/batch', {
      documents: docs,
    }),

  batchEmbed: (ids: number[]) => apiClient.post('/documents/batch/embed', { documentIds: ids }),

  batchCreateAndEmbed: (params: {
    collectionId?: number;
    documents: Array<{
      title: string;
      content: string;
      collectionId?: number;
      source?: string;
      documentType?: string;
    }>;
    force?: boolean;
  }) => apiClient.post('/documents/batch/create-and-embed', params),

  uploadAndEmbed: (formData: FormData) =>
    apiClient.post('/documents/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }),

  getEmbeddingStatus: () =>
    apiClient.get<{ totalDocuments: number; withEmbeddings: number; withoutEmbeddings: number; hasMissing: boolean }>(
      '/documents/embed-vector-status'
    ),

  reembedMissing: (force?: boolean) =>
    apiClient.post<{ total: number; success: number; failed: number }>('/documents/embed-vector-reembed', null, {
      params: { force },
    }),
};
