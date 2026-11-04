const LEGACY_API_KEY_STORAGE = 'rag-api-key';
const LEGACY_API_KEY_ROLE_STORAGE = 'rag-api-key-role';

type CredentialListener = (credential: string | null) => void;

let credential: string | null = null;
const listeners = new Set<CredentialListener>();

export function getCredential(): string | null {
  return credential;
}

export function getCredentialHeaders(): Record<string, string> {
  return credential ? { 'X-API-Key': credential } : {};
}

export function setCredential(nextCredential: string): void {
  credential = nextCredential;
  listeners.forEach(listener => listener(credential));
}

export function clearCredential(): void {
  credential = null;
  // 登出 = 清掉全部痕迹：内存凭据、旧版本明文存储、会话级工作区状态。
  clearLegacyCredentialStorage();
  clearWorkspaceState();
  listeners.forEach(listener => listener(null));
}

export function subscribeToCredential(listener: CredentialListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function clearLegacyCredentialStorage(): void {
  try {
    localStorage.removeItem(LEGACY_API_KEY_STORAGE);
    localStorage.removeItem(LEGACY_API_KEY_ROLE_STORAGE);
  } catch {
    // Storage may be unavailable in restricted browser contexts.
  }
}
import { clearWorkspaceState } from '../utils/workspaceState';
