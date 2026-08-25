import { apiClient } from './client';

export interface ApiKeyIdentity {
  principalType: string;
  principalId: string;
  rootMode: boolean;
  capabilities: string[];
  credentialId?: string;
  credentialVersion?: number;
  policyVersion?: number;
  principalRole: 'NORMAL' | 'ADMIN' | null;
  collectionAccessMode: 'RESTRICTED' | 'UNRESTRICTED';
  allowedCollectionKeys: string[] | null;
}

export const authApi = {
  currentIdentity: (credential: string) =>
    apiClient.get<ApiKeyIdentity>('/auth/me', {
      headers: { 'X-API-Key': credential },
    }),
};
