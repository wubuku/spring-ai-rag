import { apiClient } from './client';
import type { LlmUsageResponse, RagMetricsResponse } from '../types/api';

export const metricsApi = {
  overview: () =>
    apiClient.get<{
      totalDocuments: number;
      totalCollections: number;
      activeConversations: number;
      avgRetrievalLatency: number;
      cacheHitRate: number;
    }>('/metrics/overview'),

  get: () => apiClient.get<RagMetricsResponse>('/metrics'),

  usage: () => apiClient.get<LlmUsageResponse>('/usage'),
};
