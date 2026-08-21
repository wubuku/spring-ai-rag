import { apiClient } from './client';

export interface EmbeddingJob {
  id: string;
  batchId?: string;
  documentId?: number;
  status?: string;
  origin?: string;
  attemptCount?: number;
  maxAttempts?: number;
  lastError?: string;
  progress?: { stage?: string };
  createdAt?: string;
}

export interface EmbeddingJobPage {
  items: EmbeddingJob[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface EmbeddingReadiness {
  collectionKey: string;
  activeEmbeddingProfileKey?: string;
  enabledDocuments: number;
  freshDocuments: number;
  queuedDocuments: number;
  runningDocuments: number;
  failedDocuments: number;
  staleOrMissingDocuments: number;
}

export interface DerivationReadiness {
  collectionKey: string;
  activeEmbeddingProfileKey: string;
  enabledDocuments: number;
  readyDocuments: number;
  keywordOnlyDocuments: number;
  indexingDocuments: number;
  localUnavailableDocuments: number;
  vectorRepairNeededDocuments: number;
  notRequestedDocuments: number;
  corruptDocuments: number;
  disabledDocuments: number;
  scannedAt: string;
}

export interface DerivationRepairPreview {
  repairId: string;
  collectionKey: string;
  previewFingerprint: string;
  previewToken: string;
  expiresAt: string;
  items: Array<{ documentId: number; action: string; reasonCode: string }>;
  actionCounts: Record<string, number>;
  skippedDocuments: number;
}

export interface DerivationRepairStatus {
  repairId: string;
  collectionKey: string;
  status: string;
  items: Array<{
    documentId: number;
    action: string;
    status: string;
    localActionStatus: string;
    vectorActionStatus: string;
    embeddingJobId?: string | null;
    resultCode?: string | null;
    error?: string | null;
  }>;
}

export const embeddingsApi = {
  listJobs: (params?: {
    page?: number;
    size?: number;
    status?: string;
    batchId?: string;
    collectionKey?: string;
  }) => apiClient.get<EmbeddingJobPage>('/embedding-jobs', { params }),

  getJob: (id: string) => apiClient.get<EmbeddingJob>(`/embedding-jobs/${id}`),

  cancelJob: (id: string) => apiClient.post<EmbeddingJob>(`/embedding-jobs/${id}/cancel`),

  retryJob: (id: string) => apiClient.post<EmbeddingJob>(`/embedding-jobs/${id}/retry`),

  readiness: (collectionKey: string) =>
    apiClient.get<EmbeddingReadiness>('/collections/embedding-readiness', {
      params: { collectionKey },
    }),

  derivationReadiness: (collectionKey: string) =>
    apiClient.get<DerivationReadiness>('/collections/derivation-readiness', {
      params: { collectionKey },
    }),

  previewRepair: (collectionKey: string) =>
    apiClient.post<DerivationRepairPreview>('/collections/derivation-repairs/preview', {
      collectionKey,
      buckets: ['CORRUPT', 'LOCAL_UNAVAILABLE'],
      vectorConditions: ['FAILED', 'STALE'],
      maxDocuments: 100,
    }),

  applyRepair: (preview: DerivationRepairPreview) =>
    apiClient.post<DerivationRepairStatus>('/collections/derivation-repairs/apply', {
      repairId: preview.repairId,
      collectionKey: preview.collectionKey,
      previewToken: preview.previewToken,
      previewFingerprint: preview.previewFingerprint,
    }),
};
