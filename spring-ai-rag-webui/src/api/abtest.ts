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
  sampleCount?: number;
  winner?: string;
  startTime?: string;
  endTime?: string;
  createdAt: string;
  updatedAt?: string;
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

export const abtestApi = {
  listExperiments: (params?: { page?: number; size?: number }) =>
    apiClient.get<Experiment[]>('/experiments', { params }),

  getExperiment: (id: number) =>
    apiClient.get<Experiment>(`/experiments/${id}`),

  createExperiment: (data: CreateExperimentRequest) =>
    apiClient.post<Experiment>('/experiments', data),

  updateExperiment: (id: number, data: UpdateExperimentRequest) =>
    apiClient.put<Experiment>(`/experiments/${id}`, data),

  startExperiment: (id: number) =>
    apiClient.post<Experiment>(`/experiments/${id}/start`),

  pauseExperiment: (id: number) =>
    apiClient.post<Experiment>(`/experiments/${id}/pause`),

  stopExperiment: (id: number) =>
    apiClient.post<Experiment>(`/experiments/${id}/stop`),

  getResults: (id: number, params?: { page?: number; size?: number }) =>
    apiClient.get<ExperimentResult[]>(`/experiments/${id}/results`, { params }),

  getAnalysis: (id: number) =>
    apiClient.get<ExperimentAnalysis>(`/experiments/${id}/analysis`),

  deleteExperiment: (id: number) =>
    apiClient.delete(`/experiments/${id}`),
};
