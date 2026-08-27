import { apiClient } from './client';

export interface ApiPrincipalResponse {
  principalId: string;
  name: string;
  role: string;
  capabilities?: string[];
  allowedCollectionKeys?: string[];
  expiresAt?: string;
  requestsPerMinute?: number;
  policyVersion: number;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  lastUsedAt?: string;
  currentCredentialId?: string;
  currentCredentialVersion?: number;
  rotationPending?: boolean;
  pendingRotationId?: string;
  retiringCredentialId?: string;
  retiringCredentialVersion?: number;
  rotationExpiresAt?: string;
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
  capabilities?: string[];
  expiresAt?: string;
  allowedCollectionKeys?: string[];
  requestsPerMinute?: number;
  warning: string;
}

export interface ApiKeyCreateRequest {
  name: string;
  expiresAt: string;
  capabilities?: string[];
  allowedCollectionKeys?: string[];
  requestsPerMinute?: number;
}

export interface ApiPrincipalPolicyUpdateRequest {
  expectedPolicyVersion: number;
  name: string;
  expiresAt: string;
  capabilities?: string[];
  allowedCollectionKeys?: string[];
  requestsPerMinute?: number;
}

export interface ApiKeyRotationResponse {
  rotationId: string;
  status: 'PENDING' | 'COMPLETED' | 'CANCELED' | 'EXPIRED' | 'REVOKED';
  principalId: string;
  keyId: string;
  credentialVersion: number;
  rawKey: string | null;
  secretAvailable: boolean;
  idempotentReplay: boolean;
  currentCredentialActive: boolean;
  rotationPending: boolean;
  retiringCredentialId?: string;
  retiringCredentialVersion?: number;
  rotationExpiresAt?: string;
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

  prepareRotation: (
    keyId: string,
    overlapSeconds: number | undefined,
    idempotencyKey: string,
  ) =>
    apiClient.post<ApiKeyRotationResponse>(
      `/api-keys/${encodeURIComponent(keyId)}/rotations`,
      overlapSeconds === undefined ? {} : { overlapSeconds },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ),

  getRotation: (rotationId: string) =>
    apiClient.get<ApiKeyRotationResponse>(
      `/api-keys/rotations/${encodeURIComponent(rotationId)}`,
    ),

  completeRotation: (rotationId: string) =>
    apiClient.post<ApiKeyRotationResponse>(
      `/api-keys/rotations/${encodeURIComponent(rotationId)}/complete`,
    ),

  cancelRotation: (rotationId: string) =>
    apiClient.post<ApiKeyRotationResponse>(
      `/api-keys/rotations/${encodeURIComponent(rotationId)}/cancel`,
    ),

  updatePolicy: (principalId: string, data: ApiPrincipalPolicyUpdateRequest) =>
    apiClient.put<ApiPrincipalResponse>(
      `/api-keys/principals/${encodeURIComponent(principalId)}/policy`,
      data,
    ),
};
