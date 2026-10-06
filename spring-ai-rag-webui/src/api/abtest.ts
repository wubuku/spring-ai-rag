import { apiClient } from './client';

export type ExperimentStatus = 'DRAFT' | 'RUNNING' | 'PAUSED' | 'STOPPED' | 'COMPLETED';

export interface Experiment {
  id: number;
  experimentName: string;
  description?: string;
  status: ExperimentStatus;
  targetMetric?: string;
  trafficSplit?: Record<string, number>;
  variantNames?: string[];
  /**
   * Results actually recorded for this experiment.
   *
   * Batch 932: this used to be optional here and the page rendered
   * `sampleCount ?? 0`, so every experiment showed zero samples — the server had
   * no such field at all. It is now a real count, and required, so a future
   * regression shows up as a type error instead of a plausible zero.
   */
  sampleCount: number;
  winner?: string;
  startTime?: string;
  endTime?: string;
  createdAt: string;
  updatedAt?: string;
}

/**
 * The list envelope, same shape as the other paged list this UI reads.
 *
 * Batch 932: the list used to be typed as a bare `Experiment[]` while the call
 * passed `page`/`size` — a page parameter against an endpoint that had no
 * notion of pages. The envelope says how many experiments exist beyond the ones
 * on screen, which a bare array cannot.
 */
export interface ExperimentPage {
  items: Experiment[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ExperimentResult {
  id: number;
  experimentId: number;
  variantName: string;
  sessionId: string;
  query: string;
  metrics?: Record<string, number>;
  isConverted?: boolean;
  createdAt: string;
}

export interface VariantStats {
  variantName: string;
  sampleSize: number;
  meanValue: number;
  stdDeviation: number;
  conversionRate?: number;
  confidenceInterval?: [number, number];
}

export interface ExperimentAnalysis {
  experimentId: number;
  status: string;
  variantStats: Record<string, VariantStats>;
  winner?: string;
  confidenceLevel: number;
  isSignificant: boolean;
  recommendation?: string;
  analyzedAt: string;
}

export interface CreateExperimentRequest {
  experimentName: string;
  description?: string;
  trafficSplit: Record<string, number>;
  targetMetric?: string;
  minSampleSize?: number;
}

export interface UpdateExperimentRequest {
  description?: string;
  trafficSplit?: Record<string, number>;
  targetMetric?: string;
  minSampleSize?: number;
}

// Batch 932: every path here is mounted under /ab. `AbTestController` is
// `@RequestMapping("/rag/ab")` with `@ApiVersion("v1")`, which the version
// handler mapping composes to /api/v1/rag/ab — and this client used to call
// /api/v1/rag/experiments, so every one of these was a 404.
//
// `deleteExperiment` is gone rather than repointed: the controller has no
// DELETE mapping at all, nothing in the UI called it, and whether experiments
// should be deletable is a product question rather than a routing one.
export const abtestApi = {
  listExperiments: (params?: { page?: number; size?: number }) =>
    apiClient.get<ExperimentPage>('/ab/experiments', { params }),

  getExperiment: (id: number) =>
    apiClient.get<Experiment>(`/ab/experiments/${id}`),

  createExperiment: (data: CreateExperimentRequest) =>
    apiClient.post<Experiment>('/ab/experiments', data),

  updateExperiment: (id: number, data: UpdateExperimentRequest) =>
    apiClient.put<Experiment>(`/ab/experiments/${id}`, data),

  startExperiment: (id: number) =>
    apiClient.post(`/ab/experiments/${id}/start`),

  pauseExperiment: (id: number) =>
    apiClient.post(`/ab/experiments/${id}/pause`),

  stopExperiment: (id: number) =>
    apiClient.post(`/ab/experiments/${id}/stop`),

  getResults: (id: number, params?: { page?: number; size?: number }) =>
    apiClient.get<ExperimentResult[]>(`/ab/experiments/${id}/results`, { params }),

  getAnalysis: (id: number) =>
    apiClient.get<ExperimentAnalysis>(`/ab/experiments/${id}/analysis`),
};
