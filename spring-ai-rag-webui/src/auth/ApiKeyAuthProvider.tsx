import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi, type ApiKeyIdentity } from '../api/auth';
import {
  clearCredential,
  getCredential,
  setCredential,
  subscribeToCredential,
} from './credentialStore';
import { ApiKeyAuthContext } from './ApiKeyAuthContext';

const ROOT_PRINCIPAL_TYPE = 'ENVIRONMENT_ROOT';
const KEY_MANAGEMENT_CAPABILITY = 'API_KEY_MANAGE';

export function ApiKeyAuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [identity, setIdentity] = useState<ApiKeyIdentity | null>(null);

  useEffect(() => subscribeToCredential(nextCredential => {
    if (!nextCredential) {
      setIdentity(null);
      queryClient.clear();
    }
  }), [queryClient]);

  const unlock = useCallback(async (rawCredential: string) => {
    const candidate = rawCredential.trim();
    if (!candidate) {
      throw new Error('Root API key is required');
    }

    const response = await authApi.currentIdentity(candidate);
    const nextIdentity = response.data;
    const isRoot =
      nextIdentity.principalType === ROOT_PRINCIPAL_TYPE
      && nextIdentity.capabilities.includes(KEY_MANAGEMENT_CAPABILITY);
    if (!isRoot) {
      throw new Error('This API key cannot unlock the management console');
    }

    setCredential(candidate);
    setIdentity(nextIdentity);
  }, []);

  const logout = useCallback(() => {
    clearCredential();
  }, []);

  const value = useMemo(() => ({
    identity,
    isUnlocked: identity !== null && getCredential() !== null,
    unlock,
    logout,
  }), [identity, logout, unlock]);

  return (
    <ApiKeyAuthContext.Provider value={value}>
      {children}
    </ApiKeyAuthContext.Provider>
  );
}
