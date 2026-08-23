import { apiClient } from './client';

export interface ApiPrincipalResponse {
  principalId: string;
  name: string;
  role: string;
  allowedCollectionKeys?: string[];
  expiresAt?: string;
  requestsPerMinute?: number;
  policyVersion: number;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  lastUsedAt?: string;
  currentCredentialId?: string;
  currentCredentialVersion?: number;
  createdAt: string;
  updatedAt: string;
}

export interface ApiKeyCreatedResponse {
  keyId: string;
  principalId: string;
  credentialVersion: number;
  policyVersion: number;
  rawKey: string;
  name: string;
  expiresAt?: string;
  allowedCollectionKeys?: string[];
  requestsPerMinute?: number;
  warning: string;
}

export interface ApiKeyCreateRequest {
  name: string;
  expiresAt: string;
  allowedCollectionKeys?: string[];
  requestsPerMinute?: number;
}

export interface ApiPrincipalPolicyUpdateRequest {
  expectedPolicyVersion: number;
  name: string;
  expiresAt: string;
  allowedCollectionKeys?: string[];
  requestsPerMinute?: number;
}

export const apiKeysApi = {
  listPrincipals: () =>
    apiClient.get<ApiPrincipalResponse[]>('/api-keys/principals'),

  createKey: (data: ApiKeyCreateRequest) =>
    apiClient.post<ApiKeyCreatedResponse>('/api-keys', data),

  revokeKey: (keyId: string) =>
    apiClient.delete(`/api-keys/${encodeURIComponent(keyId)}`),

  rotateKey: (keyId: string) =>
    apiClient.post<ApiKeyCreatedResponse>(`/api-keys/${encodeURIComponent(keyId)}/rotate`),

  updatePolicy: (principalId: string, data: ApiPrincipalPolicyUpdateRequest) =>
    apiClient.put<ApiPrincipalResponse>(
      `/api-keys/principals/${encodeURIComponent(principalId)}/policy`,
      data,
    ),
};
