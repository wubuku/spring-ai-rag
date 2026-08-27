import { apiClient } from './client';

export interface Collection {
  id: number;
  collectionKey: string;
  name: string;
  description: string;
  embeddingModel: string;
  dimensions: number;
  enabled: boolean;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  documentCount: number;
  purgedAt?: string | null;
}

export interface CollectionListResponse {
  collections: Collection[];
  total: number;
  offset: number;
  limit: number;
}

export interface IntegrationCapabilities {
  principal: {
    principalType: string;
  };
  features: {
    optional: {
      collectionPurge: boolean;
    };
  };
}

export interface CollectionPurgePreview {
  previewId: string;
  collectionId: number;
  collectionKey: string;
  collectionVersion: number;
  chatCommitFenceVersion: number;
  status: 'PREVIEWED';
  documentCount: number;
  externalDocumentCount: number;
  localDocumentCount: number;
  embeddingCount: number;
  embeddingJobCount: number;
  versionCount: number;
  keywordChunkCount: number;
  repairPreviewCount: number;
  repairItemCount: number;
  derivedRowCount: number;
  documentIdempotencyOperationCount: number;
  feedbackCount: number;
  feedbackDocumentReferenceCount: number;
  documentAuditCount: number;
  collectionAuditCount: number;
  relocationMarkerCount: number;
  affectedChatSessionCount: number;
  chatHistoryCount: number;
  chatMemoryCount: number;
  chatSummaryCount: number;
  chatTurnOperationCount: number;
  activeSyncRunCount: number;
  activeDerivationRepairCount: number;
  activeChatSessionCount: number;
  unindexedChatReferenceCount: number;
  unindexedFeedbackReferenceCount: number;
  confirmationToken: string;
  fingerprint: string;
  previewExpiresAt: string;
  operationExpiresAt: string;
}

export interface CollectionPurgeApplyRequest {
  collectionKey: string;
  previewId: string;
  confirmationToken: string;
  fingerprint: string;
  expectedCollectionVersion: number;
  expectedChatCommitFenceVersion: number;
}

export interface CollectionPurgeResult {
  previewId: string;
  status: 'RETIRED';
  collectionId: number;
  collectionKey: string;
  purgedDocumentCount: number;
  purgedExternalDocumentCount: number;
  purgedLocalDocumentCount: number;
  deletedAt: string;
  purgedAt: string;
  collectionVersion: number;
}

export const collectionsApi = {
  integrationCapabilities: () =>
    apiClient.get<IntegrationCapabilities>('/integration-capabilities'),

  list: (params?: { page?: number; size?: number; query?: string }) =>
    apiClient.get<CollectionListResponse>('/collections', {
      params: {
        offset: (params?.page ?? 0) * (params?.size ?? 20),
        limit: params?.size ?? 20,
        query: params?.query || undefined,
      },
    }),

  get: (id: number) => apiClient.get<Collection>(`/collections/${id}`),
  getByKey: (collectionKey: string) =>
    apiClient.get<Collection>('/collections/by-key', { params: { collectionKey } }),

  create: (data: {
    name: string;
    collectionKey: string;
    description?: string;
    dimensions?: number;
    embeddingModel?: string;
  }) => {
    const idempotencyKey = crypto.randomUUID();
    return apiClient.post<Collection>('/collections', data, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  },

  update: (id: number, data: { name?: string; description?: string; enabled?: boolean }) =>
    apiClient.put(`/collections/${id}`, data),
  updateByKey: (collectionKey: string, data: { name?: string; description?: string; enabled?: boolean }) =>
    apiClient.put('/collections/by-key', data, { params: { collectionKey } }),

  delete: (id: number) => apiClient.delete(`/collections/${id}`),
  deleteByKey: (collectionKey: string) =>
    apiClient.delete('/collections/by-key', { params: { collectionKey } }),

  previewPurge: (collectionKey: string) =>
    apiClient.post<CollectionPurgePreview>(
      '/collections/by-key/purge/preview',
      undefined,
      { params: { collectionKey } },
    ),

  applyPurge: (request: CollectionPurgeApplyRequest) =>
    apiClient.post<CollectionPurgeResult>(
      '/collections/by-key/purge',
      request,
    ),

  addDocuments: (id: number, documentIds: number[]) =>
    apiClient.post(`/collections/${id}/documents`, { documentIds }),

  removeDocuments: (id: number, documentIds: number[]) =>
    apiClient.delete(`/collections/${id}/documents`, { data: { documentIds } }),

  export: (id: number) => apiClient.get(`/collections/${id}/export`),

  importCollection: (data: { name: string; [key: string]: unknown }) =>
    apiClient.post('/collections/import', data),
};
