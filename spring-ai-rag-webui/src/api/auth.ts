import { apiClient } from './client';

export interface ApiKeyIdentity {
  principalType: string;
  principalId: string;
  rootMode: boolean;
  capabilities: string[];
}

export const authApi = {
  currentIdentity: (credential: string) =>
    apiClient.get<ApiKeyIdentity>('/auth/me', {
      headers: { 'X-API-Key': credential },
    }),
};
