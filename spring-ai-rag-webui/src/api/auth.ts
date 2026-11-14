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
  /** WP9：当前凭据绑定的租户（root / legacy-static 为 default）。 */
  tenantId: string;
  /** WP9：运行模式（SINGLE / STRICT）；仅只读展示，不提供切换入口。 */
  tenantMode: 'SINGLE' | 'STRICT';
  /** WP9：绑定租户状态（ACTIVE / SUSPENDED / DISABLED）。 */
  tenantStatus: 'ACTIVE' | 'SUSPENDED' | 'DISABLED';
}

export const authApi = {
  currentIdentity: (credential: string) =>
    apiClient.get<ApiKeyIdentity>('/auth/me', {
      headers: { 'X-API-Key': credential },
    }),
};
