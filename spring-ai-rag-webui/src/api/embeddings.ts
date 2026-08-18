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
};
