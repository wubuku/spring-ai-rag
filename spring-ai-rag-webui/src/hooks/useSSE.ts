'use client';

import { useEffect, useRef, useCallback, useState } from 'react';
import type { ChatStreamChunkEvent, ChatStreamSourcesEvent, ChatSource } from '../types/api';

interface UseChatSSEOptions {
  onChunk?: (content: string) => void;
  onSources?: (sources: ChatSource[], conversationId: string) => void;
  onError?: (error: string) => void;
  onDone?: () => void;
}

interface UseChatSSEReturn {
  isConnected: boolean;
  send: (message: string, collectionId?: number, conversationId?: string) => void;
  close: () => void;
}

/**
 * SSE hook for RAG chat streaming.
 *
 * Uses fetch + ReadableStream (POST) instead of EventSource (GET-only),
 * because the backend /chat/stream endpoint is @PostMapping.
 *
 * SSE format (from backend RagChatController):
 *   event: trace
 *   data: <traceId>
 *   event: done
 *   data: {"traceId":"...","status":"complete"}
 *   data: <chunk content>   ← unnamed event → treated as 'message' type → onChunk
 */
export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
  const { onChunk, onSources, onError, onDone } = options;
  const [isConnected, setIsConnected] = useState(false);
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);
  const accumulatedContentRef = useRef<string>('');

  // Use refs to store callbacks to avoid stale closures
  // This ensures send() always uses the latest callbacks even if it's recreated
  const onChunkRef = useRef(onChunk);
  const onSourcesRef = useRef(onSources);
  const onErrorRef = useRef(onError);
  const onDoneRef = useRef(onDone);

  // Update refs when callbacks change
  onChunkRef.current = onChunk;
  onSourcesRef.current = onSources;
  onErrorRef.current = onError;
  onDoneRef.current = onDone;

  const close = useCallback(() => {
    if (readerRef.current) {
      readerRef.current.cancel();
      readerRef.current = null;
    }
    setIsConnected(false);
  }, []);

  const send = useCallback(
    async (message: string, collectionId?: number, conversationId?: string) => {
      close();
      setIsConnected(true);
      accumulatedContentRef.current = '';

      let eventType = 'message';
      let eventData = '';
      let buffer = '';

      try {
        const response = await fetch('/api/v1/rag/chat/stream', {
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

        const textDecoder = new TextDecoder();
        const reader = response.body!.getReader();
        readerRef.current = reader;

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += textDecoder.decode(value, { stream: true });

          // Process complete lines (lines ending with \n)
          while (buffer.includes('\n')) {
            const newlineIdx = buffer.indexOf('\n');
            const line = buffer.slice(0, newlineIdx);
            buffer = buffer.slice(newlineIdx + 1);

            const colonIdx = line.indexOf(':');
            if (colonIdx === -1) {
              // Empty line → SSE event terminator
              // Emit pending event if any
              if (eventData) {
                if (eventType === 'done') {
                  try {
                    const parsed = JSON.parse(eventData) as { traceId?: string; status?: string };
                    if (parsed.status === 'complete') {
                      onDoneRef.current?.();
                    }
                  } catch {
                    // ignore
                  }
                } else if (eventType !== 'trace') {
                  // 'message' event: treat as content chunk
                  try {
                    const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
                    if (parsed.type === 'chunk') {
                      accumulatedContentRef.current += parsed.content;
                      onChunkRef.current?.(parsed.content);
                    } else if (parsed.type === 'sources') {
                      onSourcesRef.current?.(parsed.sources, parsed.conversationId);
                    }
                  } catch {
                    // Not JSON — treat raw data as a text chunk
                    accumulatedContentRef.current += eventData;
                    onChunkRef.current?.(eventData);
                  }
                }
                eventData = '';
              }
              eventType = 'message';
              continue;
            }

            const field = line.slice(0, colonIdx);
            // strip leading space (SSE spec) and trailing \r
            const value2 = line.slice(colonIdx + 1).replace(/^\s+/, '').replace(/\r$/, '');

            if (field === 'event') {
              // Emit pending event before starting new event type
              if (eventData) {
                if (eventType === 'done') {
                  try {
                    const parsed = JSON.parse(eventData) as { traceId?: string; status?: string };
                    if (parsed.status === 'complete') {
                      onDoneRef.current?.();
                    }
                  } catch {
                    // ignore
                  }
                } else if (eventType !== 'trace') {
                  try {
                    const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
                    if (parsed.type === 'chunk') {
                      accumulatedContentRef.current += parsed.content;
                      onChunkRef.current?.(parsed.content);
                    } else if (parsed.type === 'sources') {
                      onSourcesRef.current?.(parsed.sources, parsed.conversationId);
                    }
                  } catch {
                    accumulatedContentRef.current += eventData;
                    onChunkRef.current?.(eventData);
                  }
                }
                eventData = '';
              }
              eventType = value2;
            } else if (field === 'data') {
              // Emit pending event before new data
              if (eventData) {
                if (eventType === 'done') {
                  try {
                    const parsed = JSON.parse(eventData) as { traceId?: string; status?: string };
                    if (parsed.status === 'complete') {
                      onDoneRef.current?.();
                    }
                  } catch {
                    // ignore
                  }
                } else if (eventType !== 'trace') {
                  try {
                    const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
                    if (parsed.type === 'chunk') {
                      accumulatedContentRef.current += parsed.content;
                      onChunkRef.current?.(parsed.content);
                    } else if (parsed.type === 'sources') {
                      onSourcesRef.current?.(parsed.sources, parsed.conversationId);
                    }
                  } catch {
                    accumulatedContentRef.current += eventData;
                    onChunkRef.current?.(eventData);
                  }
                }
                eventData = '';
              }
              // If eventType is 'done', immediately process this data as the done event payload
              if (eventType === 'done') {
                try {
                  const parsed = JSON.parse(value2) as { traceId?: string; status?: string };
                  if (parsed.status === 'complete') {
                    onDoneRef.current?.();
                  }
                } catch {
                  // ignore
                }
                eventType = 'message';
                eventData = '';
              } else {
                eventData = value2;
              }
            }
            // Other fields ignored
          }
        }

        // Process remaining buffer at end
        if (buffer.trim() && eventData) {
          if (eventType !== 'trace') {
            try {
              const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
              if (parsed.type === 'chunk') {
                accumulatedContentRef.current += parsed.content;
                onChunkRef.current?.(parsed.content);
              } else if (parsed.type === 'sources') {
                onSourcesRef.current?.(parsed.sources, parsed.conversationId);
              }
            } catch {
              accumulatedContentRef.current += eventData;
              onChunkRef.current?.(eventData);
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
    [close]
  );

  useEffect(() => {
    return () => close();
  }, [close]);

  return { isConnected, send, close };
}
