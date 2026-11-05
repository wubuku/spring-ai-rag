import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import axios, { AxiosError, AxiosHeaders } from 'axios';
import { apiClient, ApiRequestError } from './client';
import { clearCredential, getCredential } from '../auth/credentialStore';

vi.mock('../auth/credentialStore', () => ({
  getCredential: vi.fn(),
  clearCredential: vi.fn(),
}));

interface RequestHandler {
  fulfilled: (config: { headers: AxiosHeaders }) => { headers: AxiosHeaders };
}

interface ResponseHandler {
  rejected: (error: {
    response?: {
      status?: number;
      headers?: AxiosHeaders;
      data?: {
        detail?: string;
        message?: string;
        failureDetails?: { reason?: string; retryable?: boolean };
      };
    };
    message?: string;
  }) => Promise<never>;
}

function requestHandler(): RequestHandler {
  const handlers = (
    apiClient.interceptors.request as unknown as {
      handlers: Array<RequestHandler | null>;
    }
  ).handlers;
  const handler = handlers.find(entry => entry !== null);
  if (!handler) {
    throw new Error('No request interceptor registered');
  }
  return handler;
}

function responseRejectionHandler(): ResponseHandler {
  const handlers = (
    apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected: ResponseHandler['rejected'] } | null>;
    }
  ).handlers;
  // axios-retry 也会注册响应拦截器；这里按 401 处理分支锚定错误归一化拦截器
  const handler = handlers
    .filter((entry): entry is NonNullable<typeof entry> => entry !== null)
    .find(entry => entry.rejected.toString().includes('401'));
  if (!handler) {
    throw new Error('No credential-clearing response interceptor registered');
  }
  return handler;
}

describe('apiClient credential propagation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (axios.defaults.headers.common as Record<string, unknown>) = {};
  });

  it('injects the stored credential as X-API-Key when unset', () => {
    vi.mocked(getCredential).mockReturnValue('secret-key');

    const config = requestHandler().fulfilled({ headers: new AxiosHeaders() });

    expect(config.headers.get('X-API-Key')).toBe('secret-key');
  });

  it('keeps an explicitly provided X-API-Key over the stored credential', () => {
    vi.mocked(getCredential).mockReturnValue('stored');

    const headers = new AxiosHeaders();
    headers.set('X-API-Key', 'explicit');

    const config = requestHandler().fulfilled({ headers });

    expect(config.headers.get('X-API-Key')).toBe('explicit');
  });

  it('keeps Authorization headers untouched when a credential exists', () => {
    vi.mocked(getCredential).mockReturnValue('stored');

    const headers = new AxiosHeaders();
    headers.set('Authorization', 'Bearer token');

    const config = requestHandler().fulfilled({ headers });

    expect(config.headers.get('Authorization')).toBe('Bearer token');
    expect(config.headers.has('X-API-Key')).toBe(false);
  });

  it('omits X-API-Key entirely when no credential is stored', () => {
    vi.mocked(getCredential).mockReturnValue(null);

    const config = requestHandler().fulfilled({ headers: new AxiosHeaders() });

    expect(config.headers.has('X-API-Key')).toBe(false);
  });
});

describe('apiClient error normalization', () => {
  beforeEach(() => vi.clearAllMocks());

  it('clears credentials and prefers the detail field on 401', async () => {
    const error = await responseRejectionHandler().rejected({
      response: { status: 401, data: { detail: 'expired key' } },
      message: 'Request failed',
    }).catch((caught: Error) => caught);

    expect(clearCredential).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toBe('expired key');
  });

  it('falls back to message field then axios message without clearing on other statuses', async () => {
    const withMessage = await responseRejectionHandler().rejected({
      response: { status: 500, data: { message: 'boom' } },
      message: 'Request failed',
    }).catch((caught: Error) => caught);

    expect((withMessage as Error).message).toBe('boom');
    expect(clearCredential).not.toHaveBeenCalled();

    const withAxiosMessage = await responseRejectionHandler().rejected({
      response: { status: 500, data: {} },
      message: 'Network Error',
    }).catch((caught: Error) => caught);

    expect((withAxiosMessage as Error).message).toBe('Network Error');
    expect(clearCredential).not.toHaveBeenCalled();
  });

  it('carries the failure class and retry-after into the normalized error', async () => {
    const headers = new AxiosHeaders({
      'X-RAG-Failure-Class': 'RATE_LIMITED',
      'Retry-After': '2',
    });

    const error = await responseRejectionHandler().rejected({
      response: {
        status: 429,
        headers,
        data: {
          detail: 'slow down',
          failureDetails: { reason: 'UPSTREAM_RATE_LIMITED', retryable: true },
        },
      },
      message: 'Request failed',
    }).catch((caught: unknown) => caught);

    // 页面靠这些字段选本地化文案与重试入口；只保留 message 会把
    // "限流，稍后重试"压成一句通用错误。
    expect(error).toBeInstanceOf(ApiRequestError);
    expect(error).toMatchObject({
      status: 429,
      failureClass: 'RATE_LIMITED',
      reason: 'UPSTREAM_RATE_LIMITED',
      retryable: true,
      retryAfterMs: 2000,
    });
  });

  it('ignores an unknown failure class instead of failing to normalize', async () => {
    const headers = new AxiosHeaders({ 'X-RAG-Failure-Class': 'SOMETHING_NEW' });

    const error = await responseRejectionHandler().rejected({
      response: { status: 500, headers, data: {} },
      message: 'Network Error',
    }).catch((caught: unknown) => caught);

    expect((error as Error).message).toBe('Network Error');
    expect((error as { failureClass?: string }).failureClass).toBeUndefined();
  });
});

describe('apiClient retry behaviour (real axios-retry flow)', () => {
  const originalAdapter = apiClient.defaults.adapter;
  const originalConsoleWarn = console.warn;

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter;
    console.warn = originalConsoleWarn;
  });

  // axios-retry 依赖 error.config 判断重试，必须携带请求 config。
  function errorResponse(status: number, config: never) {
    return new AxiosError(
      'Request failed',
      undefined,
      config,
      undefined,
      {
        status,
        statusText: 'error',
        data: {},
        headers: new AxiosHeaders(),
        config,
      },
    );
  }

  it('retries a 5xx response once and then succeeds', async () => {
    let attempts = 0;
    apiClient.defaults.adapter = async (config) => {
      attempts += 1;
      if (attempts === 1) {
        throw errorResponse(502, config);
      }
      return {
        data: { ok: true },
        status: 200,
        statusText: 'ok',
        headers: {},
        config,
      } as never;
    };

    const response = await apiClient.get('/health');

    expect(response.data).toEqual({ ok: true });
    expect(attempts).toBe(2);
  });

  it('does not retry a 4xx response', async () => {
    let attempts = 0;
    apiClient.defaults.adapter = async (config) => {
      attempts += 1;
      throw errorResponse(404, config);
    };

    await expect(apiClient.get('/missing')).rejects.toThrow();
    expect(attempts).toBe(1);
  });

  it('keeps the failure class after 5xx retries are exhausted', async () => {
    // 5xx 会先被 axios-retry 消耗掉 4 次尝试。用户最终看到的仍然是
    // ApiRequestError，因此重试耗尽后分类头不能丢，否则页面会退回成
    // "加载失败 + 原始 detail"，又变成技术文案。
    apiClient.defaults.adapter = async (config) => {
      throw new AxiosError(
        'Request failed',
        undefined,
        config,
        undefined,
        {
          status: 503,
          statusText: 'error',
          data: { detail: 'dependency down' },
          headers: new AxiosHeaders({ 'X-RAG-Failure-Class': 'DEPENDENCY_UNAVAILABLE' }),
          config,
        },
      );
    };

    const error = await apiClient.get('/health').catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiRequestError);
    expect((error as ApiRequestError).message).toBe('dependency down');
    expect((error as ApiRequestError).failureClass).toBe('DEPENDENCY_UNAVAILABLE');
  }, 10_000);

  it('logs each retry attempt through console.warn', async () => {
    const warnSpy = vi.fn();
    console.warn = warnSpy;
    let attempts = 0;
    apiClient.defaults.adapter = async (config) => {
      attempts += 1;
      if (attempts < 3) {
        throw errorResponse(503, config);
      }
      return {
        data: { ok: true },
        status: 200,
        statusText: 'ok',
        headers: {},
        config,
      } as never;
    };

    const response = await apiClient.get('/health');

    expect(response.data).toEqual({ ok: true });
    expect(attempts).toBe(3);
    expect(warnSpy).toHaveBeenCalledTimes(2);
    expect(String(warnSpy.mock.calls[0][0])).toContain('retrying (1/3)');
  });
});
