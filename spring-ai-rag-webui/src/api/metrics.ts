import { apiClient } from './client';

export const metricsApi = {
  overview: () =>
    apiClient.get<{
      totalDocuments: number;
      totalCollections: number;
      activeConversations: number;
      avgRetrievalLatency: number;
      cacheHitRate: number;
    }>('/metrics/overview'),

  get: () => apiClient.get('/metrics'),
};
