import axios from 'axios';
import axiosRetry from 'axios-retry';

const BASE_URL = '/api/v1/rag';

export const apiClient = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000, // 30 second default timeout
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
    const message = error.response?.data?.detail ?? error.message;
    return Promise.reject(new Error(message));
  }
);
