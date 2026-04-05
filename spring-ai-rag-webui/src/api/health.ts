import { apiClient } from './client';

export interface HealthResponse {
  status: 'UP' | 'DOWN';
  timestamp: string;
  components: {
    database: 'UP' | 'DOWN';
    pgvector: 'UP' | 'DOWN';
    tables: string;
    cache: 'UP' | 'DOWN';
  };
}

export const healthApi = {
  get: () => apiClient.get<HealthResponse>('/health'),
};
