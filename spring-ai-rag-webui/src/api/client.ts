import axios from 'axios';
import axiosRetry from 'axios-retry';
import { clearCredential, getCredential } from '../auth/credentialStore';

const BASE_URL = '/api/v1/rag';

export const apiClient = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000, // 30 second default timeout
});

apiClient.interceptors.request.use(config => {
  const credential = getCredential();
  if (credential
      && !config.headers.has('X-API-Key')
      && !config.headers.has('Authorization')) {
    config.headers.set('X-API-Key', credential);
  }
  return config;
});

// Configure retry behavior
axiosRetry(apiClient, {
  retries: 3,
  retryDelay: (retryCount) => {
    // Exponential backoff: 1s, 2s, 4s
    return retryCount * 1000;
  },
  retryCondition: (error) => {
    // Retry on network errors or 5xx server errors
    if (axiosRetry.isNetworkError(error)) {
      return true;
    }
    const status = error.response?.status;
    return status !== undefined && status >= 500;
  },
  onRetry: (retryCount, error) => {
    console.warn(`Request failed, retrying (${retryCount}/3):`, error.message);
  },
});

// Response interceptor for error handling
apiClient.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      clearCredential();
    }
    const message = error.response?.data?.detail ?? error.message;
    return Promise.reject(new Error(message));
  }
);
