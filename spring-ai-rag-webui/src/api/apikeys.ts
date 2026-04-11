import { apiClient } from './client';

export interface ApiKeyResponse {
  keyId: string;
  name: string;
  createdAt: string;
  lastUsedAt?: string;
  expiresAt?: string;
  enabled: boolean;
}

export interface ApiKeyCreatedResponse {
  keyId: string;
  rawKey: string;
  name: string;
  expiresAt?: string;
  warning: string;
}

export interface ApiKeyCreateRequest {
  name: string;
  expiresAt?: string;
}

export const apiKeysApi = {
  listKeys: () =>
    apiClient.get<ApiKeyResponse[]>('/api-keys'),

  createKey: (data: ApiKeyCreateRequest) =>
    apiClient.post<ApiKeyCreatedResponse>('/api-keys', data),

  revokeKey: (keyId: string) =>
    apiClient.delete(`/api-keys/${encodeURIComponent(keyId)}`),

  rotateKey: (keyId: string) =>
    apiClient.post<ApiKeyCreatedResponse>(`/api-keys/${encodeURIComponent(keyId)}/rotate`),
};
