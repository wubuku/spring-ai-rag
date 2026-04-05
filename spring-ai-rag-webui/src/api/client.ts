import axios from 'axios';

const BASE_URL = '/api/v1/rag';

export const apiClient = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

// Response interceptor for error handling
apiClient.interceptors.response.use(
  response => response,
  error => {
    const message = error.response?.data?.detail ?? error.message;
    return Promise.reject(new Error(message));
  }
);
