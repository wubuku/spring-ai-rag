import { useCallback, useEffect, useRef, useState } from 'react';
import { getCredentialHeaders } from '../auth/credentialStore';
import type {
  ChatMode,
  ChatSource,
  CollectionScopeMode,
} from '../types/api';

export interface ChatToolStartEvent {
  tool: string;
  toolCallId?: string;
  query?: string;
}

export interface ChatToolResultEvent {
  tool: string;
  toolCallId?: string;
  resultCount: number;
  elapsedMs: number;
}

export interface ChatDoneEvent {
  traceId?: string;
  status: 'complete';
  turnId?: string;
  idempotentReplay?: boolean;
  sessionId?: string;
  requestedModel?: string;
  resolvedModel?: string;
  mode?: ChatMode;
  usage?: Record<string, unknown>;
  finishReason?: string;
  metadata?: Record<string, unknown>;
}

export interface ChatErrorEvent {
  message: string;
  code?: string;
  traceId?: string;
  sessionId?: string;
  status?: number;
}

export interface UseChatSSEOptions {
  onChunk?: (content: string) => void;
  onSources?: (sources: ChatSource[], sessionId?: string) => void;
  onToolStart?: (event: ChatToolStartEvent) => void;
  onToolResult?: (event: ChatToolResultEvent) => void;
  onTurnClaimed?: (turnId: string) => void;
  onRetry?: () => void;
  onError?: (error: string, event?: ChatErrorEvent) => void;
  onDone?: (event: ChatDoneEvent) => void;
}

export interface ChatSSESendOptions {
  message: string;
  collectionIds?: number[] | number;
  sessionId?: string;
  /** @deprecated use sessionId */
  conversationId?: string;
  model?: string;
  mode?: ChatMode;
  maxResults?: number;
  useHybridSearch?: boolean;
  useRerank?: boolean;
  collectionScopeMode?: CollectionScopeMode;
  collectionKeys?: string[] | string;
}

interface ChatSSESend {
  (options: ChatSSESendOptions): void;
  /** Legacy positional form retained for existing integrations. */
  (
    message: string,
    collectionIds?: number[] | number,
    sessionId?: string,
    model?: string,
    collectionKeys?: string[] | string
  ): void;
}

export interface UseChatSSEReturn {
  isConnected: boolean;
  send: ChatSSESend;
  close: () => void;
  stop: () => void;
}

type ParsedSseEvent = {
  type: string;
  data: string;
};

/**
 * Streams the structured chat SSE contract.
 *
 * The parser deliberately accepts both the current event names and the old
 * OpenAI-compatible `data: {"type":"chunk"}` shape while deployments roll
 * forward. Terminal events are idempotent, so a proxy duplicate cannot mark
 * a turn complete twice.
 */
export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
  const MAX_ATTEMPTS = 2;
  const MAX_RETRY_WAIT_MS = 60_000;
  const [isConnected, setIsConnected] = useState(false);
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const generationRef = useRef(0);
  const terminalRef = useRef(false);

  const onChunkRef = useRef(options.onChunk);
  const onSourcesRef = useRef(options.onSources);
  const onToolStartRef = useRef(options.onToolStart);
  const onToolResultRef = useRef(options.onToolResult);
  const onTurnClaimedRef = useRef(options.onTurnClaimed);
  const onRetryRef = useRef(options.onRetry);
  const onErrorRef = useRef(options.onError);
  const onDoneRef = useRef(options.onDone);

  onChunkRef.current = options.onChunk;
  onSourcesRef.current = options.onSources;
  onToolStartRef.current = options.onToolStart;
  onToolResultRef.current = options.onToolResult;
  onTurnClaimedRef.current = options.onTurnClaimed;
  onRetryRef.current = options.onRetry;
  onErrorRef.current = options.onError;
  onDoneRef.current = options.onDone;

  const close = useCallback(() => {
    generationRef.current += 1;
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    const reader = readerRef.current;
    readerRef.current = null;
    if (reader) {
      try {
        void Promise.resolve(reader.cancel()).catch(() => undefined);
      } catch {
        // A minimal test double or browser implementation may throw synchronously.
      }
    }
    terminalRef.current = true;
    setIsConnected(false);
  }, []);

  useEffect(() => close, [close]);

  const send = useCallback(
    async (
      request: ChatSSESendOptions | string,
      collectionIds?: number[] | number,
      sessionId?: string,
      model?: string,
      collectionKeys?: string[] | string,
    ) => {
      close();
      const generation = generationRef.current;
      terminalRef.current = false;
      setIsConnected(true);

      const sendOptions: ChatSSESendOptions =
        typeof request === 'string'
          ? { message: request, collectionIds, sessionId, model, collectionKeys }
          : request;
      const normalizedIds = normalizeArray(sendOptions.collectionIds);
      const normalizedKeys = normalizeArray(sendOptions.collectionKeys);
      const controller = new AbortController();
      abortControllerRef.current = controller;

      const body = compactObject({
        message: sendOptions.message,
        mode: sendOptions.mode,
        collectionIds: normalizedIds,
        collectionScopeMode: sendOptions.collectionScopeMode,
        collectionKeys: normalizedKeys,
        sessionId: sendOptions.sessionId ?? sendOptions.conversationId,
        model: sendOptions.model,
        maxResults: sendOptions.maxResults,
        useHybridSearch: sendOptions.useHybridSearch,
        useRerank: sendOptions.useRerank,
      });
      const idempotencyKey = crypto.randomUUID();
      let claimedTurnId: string | undefined;
      let retryAfterDeadline = Date.now() + MAX_RETRY_WAIT_MS;
      let terminalEvent = false;
      let completed = false;
      let lastError: unknown = new Error('Chat connection failed');

      try {
        for (let attempt = 0;
          attempt < MAX_ATTEMPTS
          && generation === generationRef.current
          && !terminalRef.current;
          attempt += 1) {
          const attemptController = new AbortController();
          abortControllerRef.current = attemptController;
          try {
            const response = await fetch('/api/v1/rag/chat/stream', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
                ...getCredentialHeaders(),
              },
              body: JSON.stringify(body),
              signal: attemptController.signal,
            });
            if (!response.ok) {
              const retryAfter = retryAfterMs(response);
              throw new RetryableChatError(
                `HTTP ${response.status}`,
                response.status === 409 || response.status === 429,
                retryAfter,
                response.status,
              );
            }
            if (!response.body) {
              throw new RetryableChatError(
                'Chat stream response has no body', true, 0);
            }

            const responseTurnId = asUuid(response.headers?.get('X-RAG-Turn-Id'));
            if (!responseTurnId) {
              throw new RetryableChatError(
                'Chat response did not provide a valid turn ID',
                false,
                0,
              );
            }
            if (claimedTurnId && claimedTurnId !== responseTurnId) {
              throw new RetryableChatError(
                'Chat turn identity changed during retry',
                false,
                0,
              );
            }
            if (!claimedTurnId) {
              claimedTurnId = responseTurnId;
              onTurnClaimedRef.current?.(responseTurnId);
            }
            const reader = response.body.getReader();
            readerRef.current = reader;
            const decoder = new TextDecoder();
            let buffer = '';
            let doneEvent = false;

            while (generation === generationRef.current
              && !terminalRef.current) {
              const { done, value } = await reader.read();
              if (done) {
                buffer += decoder.decode();
                const tail = parseSseBlock(buffer);
                if (tail) {
                  handleEvent(tail, responseTurnId);
                  doneEvent = tail.type === 'done';
                }
                break;
              }
              buffer += decoder.decode(value, { stream: true });
              const parsed = drainSseBlocks(buffer);
              buffer = parsed.rest;
              parsed.events.forEach(event => {
                if (event.type === 'done') doneEvent = true;
                handleEvent(event, responseTurnId);
              });
            }
            if (terminalEvent) {
              completed = true;
              break;
            }
            if (generation !== generationRef.current || terminalRef.current) {
              break;
            }
            if (!doneEvent) {
              const tail = parseSseBlock(buffer);
              if (tail) {
                handleEvent(tail, responseTurnId);
                doneEvent = tail.type === 'done';
              }
            }
            if (!doneEvent) {
              throw new RetryableChatError(
                'Chat stream ended before the done event', true, 0);
            }
            completed = true;
            break;
          } catch (error) {
            lastError = error;
            if (attemptController.signal.aborted
              || generation !== generationRef.current
              || terminalRef.current) {
              break;
            }
            const retryable = error instanceof RetryableChatError
              ? error.retryable
              : true;
            if (!retryable || attempt + 1 >= MAX_ATTEMPTS) {
              break;
            }
            onRetryRef.current?.();
            const waitMs = error instanceof RetryableChatError
              ? error.retryAfterMs
              : 0;
            if (waitMs > 0) {
              const bounded = Math.min(
                waitMs,
                Math.max(0, retryAfterDeadline - Date.now()));
              if (bounded <= 0) break;
              await sleep(bounded);
            }
          } finally {
            readerRef.current = null;
            if (abortControllerRef.current === attemptController) {
              abortControllerRef.current = null;
            }
          }
        }
        if (!completed
          && !terminalEvent
          && generation === generationRef.current
          && !terminalRef.current) {
          const event: ChatErrorEvent = {
            message: lastError instanceof Error
              ? lastError.message
              : 'Connection error',
            status: lastError instanceof RetryableChatError
              ? lastError.status
              : undefined,
          };
          onErrorRef.current?.(event.message, event);
        }
      } finally {
        retryAfterDeadline = 0;
        if (generation === generationRef.current) {
          setIsConnected(false);
          readerRef.current = null;
          abortControllerRef.current = null;
        }
      }

      function handleEvent(event: ParsedSseEvent, responseTurnId?: string) {
        if (generation !== generationRef.current || terminalRef.current) {
          return;
        }
        const payload = parseJson(event.data);
        if (event.type === 'content' || event.type === 'chunk') {
          const content = extractContent(payload);
          if (content) {
            onChunkRef.current?.(content);
          }
          return;
        }
        if (event.type === 'tool_start') {
          const tool = asToolStart(payload);
          if (tool) onToolStartRef.current?.(tool);
          return;
        }
        if (event.type === 'tool_result') {
          const tool = asToolResult(payload);
          if (tool) onToolResultRef.current?.(tool);
          return;
        }
        if (event.type === 'sources') {
          const sources = asSources(payload);
          if (sources) {
            onSourcesRef.current?.(sources, asString(payload?.sessionId));
          }
          return;
        }
        if (event.type === 'done') {
          const done = asDone(payload);
          if (done && done.status === 'complete') {
            if (!responseTurnId || !done.turnId
              || responseTurnId !== done.turnId) {
              throw new RetryableChatError(
                'Chat turn identity changed during replay', false, 0);
            }
            terminalRef.current = true;
            onDoneRef.current?.(done);
          }
          return;
        }
        if (event.type === 'error') {
          const failure = asError(payload);
          terminalEvent = true;
          terminalRef.current = true;
          onErrorRef.current?.(failure.message, failure);
        }
      }
    },
    [close],
  );

  return { isConnected, send, close, stop: close };
}

class RetryableChatError extends Error {
  readonly retryable: boolean;
  readonly retryAfterMs: number;
  readonly status?: number;

  constructor(
    message: string,
    retryable: boolean,
    retryAfterMs: number,
    status?: number,
  ) {
    super(message);
    this.retryable = retryable;
    this.retryAfterMs = retryAfterMs;
    this.status = status;
  }
}

function retryAfterMs(response: Response): number {
  const value = response.headers?.get('Retry-After');
  if (!value) return 0;
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds > 0
    ? Math.ceil(seconds * 1_000)
    : 0;
}

function asUuid(value: string | null | undefined): string | undefined {
  if (!value) return undefined;
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
    ? value
    : undefined;
}

function sleep(durationMs: number): Promise<void> {
  return new Promise(resolve => {
    window.setTimeout(resolve, durationMs);
  });
}

function normalizeArray<T>(value: T[] | T | undefined): T[] | undefined {
  if (value === undefined || value === null) return undefined;
  const values = Array.isArray(value) ? value : [value];
  return values.length > 0 ? values : undefined;
}

function compactObject(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined),
  );
}

function drainSseBlocks(buffer: string): {
  events: ParsedSseEvent[];
  rest: string;
} {
  const events: ParsedSseEvent[] = [];
  let rest = buffer;
  while (true) {
    const match = rest.match(/\r?\n\r?\n/);
    if (!match || match.index === undefined) break;
    const block = rest.slice(0, match.index);
    rest = rest.slice(match.index + match[0].length);
    const parsed = parseSseBlock(block);
    if (parsed) events.push(parsed);
  }
  return { events, rest };
}

function parseSseBlock(block: string): ParsedSseEvent | null {
  if (!block.trim()) return null;
  let type = 'content';
  const dataLines: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) {
      type = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
    }
  }
  return dataLines.length > 0 ? { type, data: dataLines.join('\n') } : null;
}

function parseJson(value: string): any {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function extractContent(payload: any): string | null {
  if (typeof payload?.content === 'string') return payload.content;
  return typeof payload?.choices?.[0]?.delta?.content === 'string'
    ? payload.choices[0].delta.content
    : null;
}

function asSources(payload: any): ChatSource[] | null {
  return Array.isArray(payload?.sources) ? payload.sources as ChatSource[] : null;
}

function asToolStart(payload: any): ChatToolStartEvent | null {
  return typeof payload?.tool === 'string'
    ? {
        tool: payload.tool,
        toolCallId: asString(payload.toolCallId),
        query: asString(payload.query),
      }
    : null;
}

function asToolResult(payload: any): ChatToolResultEvent | null {
  return typeof payload?.tool === 'string'
    ? {
        tool: payload.tool,
        toolCallId: asString(payload.toolCallId),
        resultCount: Number(payload.resultCount ?? 0),
        elapsedMs: Number(payload.elapsedMs ?? 0),
      }
    : null;
}

function asDone(payload: any): ChatDoneEvent | null {
  return payload && typeof payload === 'object'
    ? payload as ChatDoneEvent
    : null;
}

function asError(payload: any): ChatErrorEvent {
  const nested = payload?.error;
  return {
    message: asString(nested?.message) ?? asString(payload?.message) ?? 'Chat stream failed',
    code: asString(nested?.code),
    traceId: asString(payload?.traceId),
    sessionId: asString(payload?.sessionId),
  };
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export {
  drainSseBlocks,
  extractContent,
  parseSseBlock,
};
