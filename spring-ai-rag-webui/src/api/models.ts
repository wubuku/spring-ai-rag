import { apiClient } from './client';

export interface ModelInfo {
  ref: string;
  provider: string;
  providerName: string;
  modelId: string;
  name: string;
  apiType: string;
  available: boolean;
  unavailableReason?: string;
  reasoning?: boolean;
  contextWindow?: number;
  maxTokens?: number;
  source?: 'configured' | 'legacy';
  capabilities?: {
    streaming?: boolean;
    toolCalling?: boolean;
  };
}

export interface ModelListResponse {
  multiModelEnabled: boolean;
  defaultProvider: string;
  defaultModel: string;
  availableProviders: string[];
  fallbackChain: string[];
  models: ModelInfo[];
}

export const modelsApi = {
  list: () => apiClient.get<ModelListResponse>('/models'),
};
