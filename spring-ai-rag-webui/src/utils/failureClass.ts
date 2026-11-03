/**
 * 后端失败分类在前端的唯一解释层。
 *
 * 后端用 `X-RAG-Failure-Class` 头（以及错误体 / SSE `error` 事件里的
 * `failureClass`、`failureDetails.reason`）表达"这次失败属于哪一类"。
 * 在这之前，前端只读 `detail`/`message`，于是限流、上游不可用、超时
 * 全都变成一句带英文技术细节的通用错误，用户既看不懂也不知道能不能重试。
 *
 * 这个文件把三件事收在一处：
 *  - 解析头部/事件里的分类，避免每个页面各写一遍字符串比较；
 *  - 分类 → 本地化文案 key 的映射，中英文案成对维护；
 *  - 哪些分类可以把服务端原始 reason 附在文案后面（业务校验可以，
 *    上游技术原因不往用户脸上贴）。
 */

import { usableReason } from './failureReason';

export const FAILURE_CLASS_HEADER = 'X-RAG-Failure-Class';

export const FAILURE_CLASSES = [
  'CLIENT_REQUEST',
  'AUTHENTICATION',
  'AUTHORIZATION',
  'NOT_FOUND',
  'CONFLICT',
  'RATE_LIMITED',
  'DEPENDENCY_UNAVAILABLE',
  'TIMEOUT',
  'INTERNAL',
] as const;

export type FailureClass = (typeof FAILURE_CLASSES)[number];

/** 一次失败里值得展示给用户的信号，来源可以是响应头、错误体或 SSE 事件。 */
export interface FailureSignal {
  failureClass?: FailureClass;
  /** 上游失败原因码，例如 `UPSTREAM_TIMEOUT`；比分类更具体。 */
  reason?: string;
  retryable?: boolean;
  code?: string;
  status?: number;
  retryAfterMs?: number;
}

const CLASS_SET: ReadonlySet<string> = new Set<string>(FAILURE_CLASSES);

/**
 * 解析一个可能是任意值的分类字段。
 *
 * 头名大小写、未知枚举、空串都当成"没有分类"，不抛错：一个客户端不该
 * 因为服务端多给了个新枚举值就渲染失败。
 */
export function parseFailureClass(value: unknown): FailureClass | undefined {
  if (typeof value !== 'string') return undefined;
  const normalized = value.trim().toUpperCase();
  return CLASS_SET.has(normalized) ? (normalized as FailureClass) : undefined;
}

/**
 * 用 HTTP 状态兜底分类。
 *
 * 分类头是可选的：代理、旧版后端或手写的错误响应都可能不带
 * `X-RAG-Failure-Class`。没有头时，状态码本身已经足够把冲突、限流、
 * 上游不可用区分开，比回落成一句"出错了"更接近用户需要知道的事。
 */
export function failureClassForStatus(status: unknown): FailureClass | undefined {
  if (typeof status !== 'number') return undefined;
  switch (status) {
    case 400:
      return 'CLIENT_REQUEST';
    case 401:
      return 'AUTHENTICATION';
    case 403:
      return 'AUTHORIZATION';
    case 404:
      return 'NOT_FOUND';
    case 409:
      return 'CONFLICT';
    case 429:
      return 'RATE_LIMITED';
    case 502:
    case 503:
      return 'DEPENDENCY_UNAVAILABLE';
    case 504:
      return 'TIMEOUT';
    default:
      return undefined;
  }
}

/**
 * 上游 reason → 文案 key。reason 比 coarse class 更具体，所以优先。
 *
 * 这些字面量同时满足 `check-i18n-keys` 的引用扫描，不会把文案当成死 key。
 */
const REASON_COPY_KEY: Readonly<Record<string, string>> = {
  UPSTREAM_RATE_LIMITED: 'failures.rateLimited',
  UPSTREAM_TIMEOUT: 'failures.timeout',
  UPSTREAM_UNAVAILABLE: 'failures.dependencyUnavailable',
  UPSTREAM_CONNECTION: 'failures.connection',
  UPSTREAM_RESPONSE_INVALID: 'failures.invalidResponse',
};

const CLASS_COPY_KEY: Readonly<Record<FailureClass, string | undefined>> = {
  RATE_LIMITED: 'failures.rateLimited',
  DEPENDENCY_UNAVAILABLE: 'failures.dependencyUnavailable',
  TIMEOUT: 'failures.timeout',
  CONFLICT: 'failures.conflict',
  AUTHENTICATION: 'failures.authentication',
  AUTHORIZATION: 'failures.authorization',
  CLIENT_REQUEST: 'failures.clientRequest',
  // 这两类没有一句放之四海皆准的用户文案：页面自己的 fallback 更准确。
  NOT_FOUND: undefined,
  INTERNAL: undefined,
};

/** 该失败是否有本地化文案；没有时调用方用自己的页面级 fallback。 */
export function failureCopyKey(signal?: FailureSignal | null): string | undefined {
  if (!signal) return undefined;
  const reason = typeof signal.reason === 'string'
    ? signal.reason.trim().toUpperCase()
    : '';
  if (reason && REASON_COPY_KEY[reason]) return REASON_COPY_KEY[reason];
  return signal.failureClass ? CLASS_COPY_KEY[signal.failureClass] : undefined;
}

/**
 * 把失败翻译成一句用户能读懂的话。
 *
 * @param t i18next 的翻译函数
 * @param signal 已知的失败信号
 * @param fallbackKey 没有分类文案时使用的页面级 key
 */
export function failureCopy(
  t: (key: string) => string,
  signal: FailureSignal | null | undefined,
  fallbackKey: string,
): string {
  return t(failureCopyKey(signal) ?? fallbackKey);
}

/**
 * 和 `failureCopy` 一样，但在失败属于"用户能据此改输入"的业务校验时，
 * 把服务端原文括在文案后面（与既有 `failureMessage` 的格式一致）。
 *
 * 上游/限流/超时/鉴权不追加：那些 reason 是供应商内部细节。
 */
export function failureCopyWithDetail(
  t: (key: string) => string,
  signal: FailureSignal | null | undefined,
  fallbackKey: string,
  message: string | undefined,
): string {
  const copy = failureCopy(t, signal, fallbackKey);
  if (!canAppendServerReason(signal)) return copy;
  const reason = usableReason(message);
  if (!reason || /^HTTP \d{3}$/i.test(reason)) return copy;
  return `${copy} (${reason})`;
}

/**
 * 服务端原始 reason 是否适合附在文案后面。
 *
 * 业务校验（400/409）和未知分类保留 reason，因为那是用户需要改输入的依据；
 * 限流/上游/超时/鉴权则只给一句本地化解释——那些 reason 说的是供应商内部
 * 发生了什么，贴出来就是"英文技术栈"。
 */
export function canAppendServerReason(signal?: FailureSignal | null): boolean {
  const cls = signal?.failureClass;
  if (!cls) return true;
  return cls === 'CLIENT_REQUEST' || cls === 'CONFLICT' || cls === 'NOT_FOUND';
}

/**
 * 失败后是否值得给一个重试入口。
 *
 * 鉴权/授权/请求本身不合法重试也不会成功；其余（限流、上游不可用、超时、
 * 冲突、内部错误）都是可重试的。未知分类按可重试处理，因为这是瞬时故障
 * 最常见的样子。
 */
export function canRetryFailure(signal?: FailureSignal | null): boolean {
  const cls = signal?.failureClass;
  if (!cls) return true;
  return cls !== 'AUTHENTICATION'
    && cls !== 'AUTHORIZATION'
    && cls !== 'CLIENT_REQUEST'
    && cls !== 'NOT_FOUND';
}

/** 从任意捕获值里读分类，不假设它一定是 Error。 */
export function failureClassOf(error: unknown): FailureClass | undefined {
  if (!error || typeof error !== 'object') return undefined;
  return parseFailureClass((error as { failureClass?: unknown }).failureClass);
}

/** 从任意捕获值里组装信号。 */
export function failureSignalOf(error: unknown): FailureSignal {
  if (!error || typeof error !== 'object') return {};
  const record = error as {
    failureClass?: unknown;
    reason?: unknown;
    retryable?: unknown;
    code?: unknown;
    status?: unknown;
    retryAfterMs?: unknown;
  };
  return {
    failureClass: parseFailureClass(record.failureClass),
    reason: typeof record.reason === 'string' ? record.reason : undefined,
    retryable: typeof record.retryable === 'boolean' ? record.retryable : undefined,
    code: typeof record.code === 'string' ? record.code : undefined,
    status: typeof record.status === 'number' ? record.status : undefined,
    retryAfterMs: typeof record.retryAfterMs === 'number' ? record.retryAfterMs : undefined,
  };
}

/** 解析 `Retry-After`（秒）为毫秒；非法值返回 0。 */
export function parseRetryAfterMs(value: unknown): number {
  if (typeof value !== 'string' || value.trim() === '') return 0;
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds * 1_000) : 0;
}
