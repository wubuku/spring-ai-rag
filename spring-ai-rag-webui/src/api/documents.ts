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
  collectionKey: string | null;
  collectionName: string | null;
  chunkCount: number;
  externalId?: string | null;
  sourceNamespace?: string | null;
  sourceRevision?: string | null;
  documentRevision?: number | null;
  sourceDeletedAt?: string | null;
  processingStatus?: string | null;
  processingError?: string | null;
  embeddingFresh?: boolean;
  enabled?: boolean;
  lifecycle?: DocumentLifecycle | null;
}

export interface DocumentLifecycle {
  documentState: 'ACTIVE' | 'DISABLED' | 'TOMBSTONED' | string;
  searchability: 'READY' | 'INDEXING' | 'FAILED' | 'NOT_REQUESTED' | 'DISABLED' | string;
  localIndexStatus: string;
  embeddingStatus: string;
  activeEmbeddingProfileKey?: string | null;
  activeJobId?: string | null;
  lastErrorCode?: string | null;
  lastError?: string | null;
  retryable: boolean;
}

export interface DocumentMutationResponse {
  documentId: number;
  action: string;
  documentRevision: number;
  versionNumber: number;
  contentChanged: boolean;
  metadataChanged: boolean;
  scopeChanged: boolean;
  embeddingAction: string;
  embeddingJobId?: string | null;
  embeddingBatchId?: string | null;
  lifecycle: DocumentLifecycle;
}

export interface DocumentUpdate {
  expectedDocumentRevision: number;
  title?: string;
  content?: string;
  source?: string | null;
  metadata?: Record<string, unknown> | null;
  collectionKey?: string | null;
  embeddingPolicy?: 'SYNC' | 'ASYNC' | 'SKIP';
}

export interface DocumentListResponse {
  documents: Document[];
  total: number;
  offset: number;
  limit: number;
}

export const documentsApi = {
  list: (params: { page?: number; size?: number; collectionId?: number; collectionKey?: string; title?: string }) =>
    apiClient.get<DocumentListResponse>('/documents', { params }),

  get: (id: number) => apiClient.get<Document>(`/documents/${id}`),

  update: (id: number, request: DocumentUpdate) =>
    apiClient.patch<DocumentMutationResponse>(`/documents/${id}`, request),

  disable: (id: number, expectedDocumentRevision: number) =>
    apiClient.post<DocumentMutationResponse>(`/documents/${id}/disable`, {
      expectedDocumentRevision,
    }),

  restore: (
    id: number,
    expectedDocumentRevision: number,
    embeddingPolicy: 'SYNC' | 'ASYNC' | 'SKIP' = 'ASYNC',
  ) =>
    apiClient.post<DocumentMutationResponse>(`/documents/${id}/restore`, {
      expectedDocumentRevision,
      embeddingPolicy,
    }),

  delete: (id: number, expectedDocumentRevision: number) =>
    apiClient.delete(`/documents/${id}`, {
      params: { expectedDocumentRevision },
    }),

  embed: (id: number, force = false) =>
    apiClient.post(`/documents/${id}/embed`, null, { params: { force } }),

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

  getVersions: (id: number, page = 0, size = 20) =>
    apiClient.get<{ documentId: number; totalVersions: number; page: number; size: number; versions: DocumentVersion[] }>(
      `/documents/${id}/versions`,
      { params: { page, size } }
    ),

  getVersion: (id: number, versionNumber: number) =>
    apiClient.get<DocumentVersionDetail>(`/documents/${id}/versions/${versionNumber}`),

  restoreVersion: (
    id: number,
    versionNumber: number,
    expectedDocumentRevision: number,
    embeddingPolicy: 'SYNC' | 'ASYNC' | 'SKIP' = 'ASYNC',
    visibilityMode: 'KEEP_CURRENT' | 'SNAPSHOT' = 'KEEP_CURRENT',
  ) =>
    apiClient.post<DocumentMutationResponse>(
      `/documents/${id}/versions/${versionNumber}/restore`,
      {
        expectedDocumentRevision,
        embeddingPolicy,
        visibilityMode,
      },
    ),
};

export interface DocumentVersion {
  id: number;
  documentId: number;
  versionNumber: number;
  contentHash: string;
  size: number;
  changeType: string;
  changeDescription: string;
  createdAt: string;
  snapshotCompleteness?: 'FULL' | 'CONTENT_AND_METADATA_ONLY' | string | null;
}

export interface DocumentVersionDetail extends DocumentVersion {
  contentSnapshot: string;
}
