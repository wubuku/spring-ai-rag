import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearCredential,
  clearLegacyCredentialStorage,
  getCredential,
  getCredentialHeaders,
  setCredential,
  subscribeToCredential,
} from './credentialStore';

describe('credentialStore', () => {
  beforeEach(() => {
    clearCredential();
    localStorage.clear();
  });

  it('keeps credentials in memory and exposes header form', () => {
    setCredential('root-secret');

    expect(getCredential()).toBe('root-secret');
    expect(getCredentialHeaders()).toEqual({ 'X-API-Key': 'root-secret' });
    expect(localStorage.length).toBe(0);
  });

  it('notifies subscribers when the credential is cleared', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeToCredential(listener);

    setCredential('root-secret');
    clearCredential();
    unsubscribe();

    expect(listener).toHaveBeenLastCalledWith(null);
  });

  it('never persists the credential to localStorage or sessionStorage', () => {
    sessionStorage.clear();
    setCredential('root-secret');

    expect(localStorage.length).toBe(0);
    const sessionValues = Array.from({ length: sessionStorage.length }, (_, index) =>
      sessionStorage.getItem(sessionStorage.key(index) ?? ''));
    expect(sessionValues.join('\u0000')).not.toContain('root-secret');
  });

  it('clears legacy storage and workspace state on logout', () => {
    localStorage.setItem('rag-api-key', 'legacy-secret');
    localStorage.setItem('rag-api-key-role', 'ADMIN');
    sessionStorage.setItem('spring-ai-rag:webui:v1:routes', '{"search":"/search?q=secret"}');

    clearCredential();

    expect(getCredential()).toBeNull();
    expect(localStorage.getItem('rag-api-key')).toBeNull();
    expect(localStorage.getItem('rag-api-key-role')).toBeNull();
    expect(sessionStorage.getItem('spring-ai-rag:webui:v1:routes')).toBeNull();
  });

  it('removes legacy persisted API key entries only', () => {
    localStorage.setItem('rag-api-key', 'legacy-secret');
    localStorage.setItem('rag-api-key-role', 'ADMIN');
    localStorage.setItem('theme', 'dark');

    clearLegacyCredentialStorage();

    expect(localStorage.getItem('rag-api-key')).toBeNull();
    expect(localStorage.getItem('rag-api-key-role')).toBeNull();
    expect(localStorage.getItem('theme')).toBe('dark');
  });
});
