import { apiClient } from './client';

export type ProcessingRunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'STALE'
  | 'DEAD_LETTERED'
  | string;

export interface ProcessingStage {
  stageName: string;
  stageOrder: number;
  status: string;
  executionAttempt: number;
  progressCurrent: number;
  progressTotal: number;
  progressLabel?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  lastHeartbeatAt?: string | null;
  updatedAt?: string | null;
  lastErrorCode?: string | null;
  lastError?: string | null;
}

export interface ProcessingRun {
  runId: string;
  documentId: number;
  status: ProcessingRunStatus;
  activeStage?: string | null;
  attemptNo: number;
  executionAttempt: number;
  maxAttempts: number;
  nextRetryAt?: string | null;
  cancelRequestedAt?: string | null;
  deadLetteredAt?: string | null;
  deadLetterReason?: string | null;
  lastErrorCode?: string | null;
  lastError?: string | null;
  createdAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  updatedAt?: string | null;
  stages: ProcessingStage[];
  coalesced?: boolean | null;
}

export interface ProcessingRunPage {
  items: ProcessingRun[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const documentProcessingApi = {
  list: (documentId: number) =>
    apiClient.get<ProcessingRunPage>('/document-processing-runs', {
      params: { documentId, page: 0, size: 20 },
    }),
  cancel: (runId: string) =>
    apiClient.post<ProcessingRun>(`/document-processing-runs/${encodeURIComponent(runId)}/cancel`),
  retry: (runId: string) =>
    apiClient.post<ProcessingRun>(`/document-processing-runs/${encodeURIComponent(runId)}/retry`),
};
