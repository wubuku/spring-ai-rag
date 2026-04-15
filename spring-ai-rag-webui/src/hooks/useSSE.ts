import { useCallback, useEffect, useRef, useState } from 'react';

export interface ChatStreamChunkEvent {
  choices: Array<{
    index: number;
    delta: { content?: string; role?: string };
    finish_reason?: 'stop' | 'length';
  }>;
}

export interface ChatStreamSourcesEvent {
  sources: Array<{
    documentId: string | number;
    title?: string;
    content?: string;
    chunkText?: string;
    score?: number;
  }>;
}

export interface ChatStreamDoneEvent {
  traceId: string;
  status: 'complete';
}

export interface UseChatSSEOptions {
  onChunk?: (content: string) => void;
  onSources?: (sources: Array<{documentId: string | number; title?: string; content?: string; score?: number}>, conversationId?: string) => void;
  onError?: (error: string) => void;
  onDone?: () => void;
  apiKey?: string;
}

export interface UseChatSSEReturn {
  isConnected: boolean;
  send: (message: string, collectionId?: number, conversationId?: string, apiKey?: string) => void;
  close: () => void;
}

/**
 * SSE 流式聊天 Hook
 *
 * SSE 协议：OpenAI 兼容格式
 * - Content events: data:{"choices":[{"delta":{"content":"..."}}]}
 * - Done event:     event:done\ndata:{"traceId":"...","status":"complete"}
 * - Sources event:  event:sources\ndata:{"sources":[...]}
 * - Error event:   event:error\ndata:{"error":{"message":"..."}}
 */
export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
  const { onChunk, onSources, onError, onDone, apiKey } = options;
  const [isConnected, setIsConnected] = useState(false);
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);
  const accumulatedContentRef = useRef<string>('');

  const onChunkRef = useRef(onChunk);
  const onSourcesRef = useRef(onSources);
  const onErrorRef = useRef(onError);
  const onDoneRef = useRef(onDone);

  onChunkRef.current = onChunk;
  onSourcesRef.current = onSources;
  onErrorRef.current = onError;
  onDoneRef.current = onDone;

  // Cleanup: cancel any ongoing stream when component unmounts
  useEffect(() => {
    return () => {
      if (readerRef.current) {
        readerRef.current.cancel();
        readerRef.current = null;
      }
    };
  }, []);

  const close = useCallback(() => {
    if (readerRef.current) {
      readerRef.current.cancel();
      readerRef.current = null;
    }
    setIsConnected(false);
  }, []);

  const send = useCallback(
    async (message: string, collectionId?: number, conversationId?: string, apiKeyParam?: string) => {
      close();
      setIsConnected(true);
      accumulatedContentRef.current = '';

      // Use API key from parameter, options, or fall back to empty string
      const effectiveApiKey = apiKeyParam || apiKey || '';

      try {
        // Append apiKey to URL query string if available (for SSE auth compatibility)
        const url = effectiveApiKey
          ? `/api/v1/rag/chat/stream?apiKey=${encodeURIComponent(effectiveApiKey)}`
          : '/api/v1/rag/chat/stream';

        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            message,
            collectionId: collectionId ?? undefined,
            sessionId: conversationId ?? undefined,
          }),
        });

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const reader = response.body!.getReader();
        readerRef.current = reader;

        // Streaming SSE parsing: process events as they arrive
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          // Process complete SSE events (separated by \n\n)
          while (buffer.includes('\n\n')) {
            const eventEnd = buffer.indexOf('\n\n');
            const eventBlock = buffer.slice(0, eventEnd);
            buffer = buffer.slice(eventEnd + 2);

            // Parse the SSE event
            const event = parseSSEEvent(eventBlock);
            if (!event) continue;

            if (event.type === 'content' && event.data) {
              // OpenAI format: {"choices":[{"delta":{"content":"..."}}]}
              const content = extractContentFromChoices(event.data);
              if (content) {
                accumulatedContentRef.current += content;
                onChunkRef.current?.(content);
              }
            } else if (event.type === 'sources' && event.data) {
              // Sources event: {"sources":[...]}
              try {
                const sourcesData = JSON.parse(event.data);
                if (sourcesData.sources) {
                  onSourcesRef.current?.(sourcesData.sources);
                }
              } catch { /* ignore parse errors */ }
            } else if (event.type === 'done' && event.data) {
              // Done event: {"traceId":"...","status":"complete"}
              try {
                const doneData = JSON.parse(event.data);
                if (doneData.status === 'complete') {
                  onDoneRef.current?.();
                }
              } catch { /* ignore */ }
            } else if (event.type === 'error' && event.data) {
              // Error event: {"error":{"message":"..."}}
              try {
                const errorData = JSON.parse(event.data);
                if (errorData.error?.message) {
                  onErrorRef.current?.(errorData.error.message);
                }
              } catch { /* ignore */ }
            }
          }
        }

      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : 'Connection error';
        onErrorRef.current?.(errorMessage);
      } finally {
        setIsConnected(false);
        readerRef.current = null;
      }
    },
    [close, apiKey]
  );

  return { isConnected, send, close };
}

/**
 * SSE 事件解析器
 * 支持：
 * - data:{"choices":[{"delta":{"content":"..."}}]}   -> type: content
 * - event:sources\ndata:{"sources":[...]}          -> type: sources
 * - event:done\ndata:{"traceId":"..."}                -> type: done
 * - event:error\ndata:{"error":{...}}                  -> type: error
 */
function parseSSEEvent(block: string): { type: string; data: string } | null {
  if (!block.trim()) return null;

  let eventType = 'content'; // Default to content (OpenAI format uses no event type)
  let eventData = '';

  const lines = block.split('\n');
  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      eventData = line.slice(5); // after "data:"
    }
  }

  if (!eventData) return null;

  return { type: eventType, data: eventData };
}

/**
 * 从 OpenAI 格式的 choices 数组中提取内容
 */
function extractContentFromChoices(jsonStr: string): string | null {
  try {
    const parsed = JSON.parse(jsonStr) as ChatStreamChunkEvent;
    if (parsed.choices && parsed.choices.length > 0) {
      return parsed.choices[0].delta.content ?? null;
    }
  } catch { /* not JSON or wrong format */ }
  return null;
}
