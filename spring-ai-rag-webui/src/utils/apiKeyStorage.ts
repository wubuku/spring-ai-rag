/**
 * localStorage utilities for storing the RAG API key.
 *
 * The key is stored in plain text — acceptable for internal/dev environments.
 */

const API_KEY_STORAGE = 'rag-api-key';
const API_KEY_ROLE_STORAGE = 'rag-api-key-role';

/** Save the RAG API key and its role to localStorage. */
export const saveApiKey = (key: string, role?: string) => {
  localStorage.setItem(API_KEY_STORAGE, key);
  if (role) {
    localStorage.setItem(API_KEY_ROLE_STORAGE, role);
  }
};

/** Retrieve the stored RAG API key. Returns empty string if not set. */
export const getApiKey = (): string => {
  return localStorage.getItem(API_KEY_STORAGE) ?? '';
};

/** Retrieve the stored RAG API key role (ADMIN or NORMAL). Returns undefined if not set. */
export const getApiKeyRole = (): string | undefined => {
  return localStorage.getItem(API_KEY_ROLE_STORAGE) ?? undefined;
};

/** Remove the stored RAG API key and role. */
export const clearApiKey = () => {
  localStorage.removeItem(API_KEY_STORAGE);
  localStorage.removeItem(API_KEY_ROLE_STORAGE);
};
