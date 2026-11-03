import axios from 'axios';
import axiosRetry from 'axios-retry';
import { clearCredential, getCredential } from '../auth/credentialStore';
import {
  FAILURE_CLASS_HEADER,
  failureClassForStatus,
  parseFailureClass,
  parseRetryAfterMs,
  type FailureClass,
} from '../utils/failureClass';
import { HTTP_TIMEOUT_MS } from '../utils/timing';

/**
 * Where the WebUI's requests go.
 *
 * Batch 933: exported because three call sites outside `src/api` talk to the
 * server with `fetch` rather than through `apiClient` — the SSE chat stream, the
 * multipart upload, and the client-error report from the error boundary. Each of
 * them used to spell this string out, so the base path existed in four places and
 * a version bump would have needed four edits, none of which a type checker would
 * have pointed at. `check-single-api-base` fails if a second copy reappears.
 */
export const BASE_URL = '/api/v1/rag';

export const apiClient = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: HTTP_TIMEOUT_MS,
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

/**
 * 页面唯一需要认识的请求失败类型。
 *
 * 它保留 `Error` 的 message 语义（既有测试和 toast 都这么读），额外带上
 * 后端失败分类，让页面能把"限流/上游不可用/超时"翻成本地化文案，而不是
 * 把 `HTTP 500` 或供应商英文错误直接甩给用户。
 */
export class ApiRequestError extends Error {
  readonly status?: number;
  readonly failureClass?: FailureClass;
  readonly reason?: string;
  readonly retryable?: boolean;
  readonly retryAfterMs: number;

  constructor(message: string, signal: {
    status?: number;
    failureClass?: FailureClass;
    reason?: string;
    retryable?: boolean;
    retryAfterMs?: number;
  } = {}) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = signal.status;
    this.failureClass = signal.failureClass;
    this.reason = signal.reason;
    this.retryable = signal.retryable;
    this.retryAfterMs = signal.retryAfterMs ?? 0;
  }
}

/** 读一个响应头，兼容 AxiosHeaders 与测试里的普通对象。 */
function readHeader(headers: unknown, name: string): string | undefined {
  if (!headers || typeof headers !== 'object') return undefined;
  const get = (headers as { get?: (key: string) => unknown }).get;
  if (typeof get === 'function') {
    const value = get.call(headers, name);
    return typeof value === 'string' ? value : undefined;
  }
  const record = headers as Record<string, unknown>;
  const value = record[name] ?? record[name.toLowerCase()];
  return typeof value === 'string' ? value : undefined;
}

// Response interceptor for error handling
apiClient.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      clearCredential();
    }
    const message =
      error.response?.data?.detail
      ?? error.response?.data?.message
      ?? error.message;
    const failureDetails = error.response?.data?.failureDetails;
    return Promise.reject(new ApiRequestError(message, {
      status: error.response?.status,
      failureClass: parseFailureClass(
        readHeader(error.response?.headers, FAILURE_CLASS_HEADER))
        ?? failureClassForStatus(error.response?.status),
      reason: typeof failureDetails?.reason === 'string'
        ? failureDetails.reason
        : undefined,
      retryable: typeof failureDetails?.retryable === 'boolean'
        ? failureDetails.retryable
        : undefined,
      retryAfterMs: parseRetryAfterMs(readHeader(error.response?.headers, 'Retry-After')),
    }));
  }
);
