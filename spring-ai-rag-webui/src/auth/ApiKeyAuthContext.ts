import { createContext, useContext } from 'react';
import type { ApiKeyIdentity } from '../api/auth';

export interface ApiKeyAuthContextValue {
  identity: ApiKeyIdentity | null;
  isUnlocked: boolean;
  unlock: (credential: string) => Promise<void>;
  logout: () => void;
}

export const ApiKeyAuthContext = createContext<ApiKeyAuthContextValue | null>(null);

export function useApiKeyAuth(): ApiKeyAuthContextValue {
  const context = useContext(ApiKeyAuthContext);
  if (!context) {
    throw new Error('useApiKeyAuth must be used within ApiKeyAuthProvider');
  }
  return context;
}
