import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ApiKeyIdentity } from '../api/auth';
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

    // 解锁是一次性动作：把 axios 客户端留到这个时刻再加载，首屏就不必
    // 为一个未登录用户还看不到的请求栈付出传输和解析成本。
    const { authApi } = await import('../api/auth');
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
