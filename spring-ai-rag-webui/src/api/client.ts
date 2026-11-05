import axios from 'axios';
import axiosRetry from 'axios-retry';
import { clearCredential, getCredential } from '../auth/credentialStore';
import { BASE_URL } from './baseUrl';
import {
  FAILURE_CLASS_HEADER,
  failureClassForStatus,
  parseFailureClass,
  parseRetryAfterMs,
  type FailureClass,
} from '../utils/failureClass';
import { HTTP_TIMEOUT_MS } from '../utils/timing';

export const AUTH_EXPIRED_NOTICE_KEY = 'rag-auth-expired-notice';

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
    // axios-retry 内部用同一个实例重发请求；被重试的那次调用已经走过
    // 本拦截器，返回的 `ApiRequestError` 没有 `response` 字段。若在这里
    // 再归一化一次，5xx 重试耗尽后的 failureClass/status 会被清空，
    // 页面就退回"加载失败 + 原始 detail"的技术文案。
    if (error instanceof ApiRequestError) return Promise.reject(error);
    if (error.response?.status === 401) {
      clearCredential();
      try {
        // 401 会让受保护路由回到解锁页；留下原因，否则用户只会看到表单重置。
        sessionStorage.setItem(AUTH_EXPIRED_NOTICE_KEY, '1');
      } catch {
        // sessionStorage 在隐私模式下可能抛错；提示缺失不应阻断错误映射。
      }
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
