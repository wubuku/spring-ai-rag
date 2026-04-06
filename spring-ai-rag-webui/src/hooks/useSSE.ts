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
 * SSE format:
 *   event: trace
 *   data: <traceId>
 *   event: done
 *   data: {"traceId":"...","status":"complete"}
 *   data: <chunk content>   ← unnamed event → onmessage → type=chunk
 */
export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
  const { onChunk, onSources, onError, onDone } = options;
  const [isConnected, setIsConnected] = useState(false);
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);
  const accumulatedContentRef = useRef<string>('');

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

        // Decode the stream as UTF-8 text and parse SSE format line-by-line
        const textDecoder = new TextDecoder();
        let eventType = 'message';
        let eventData = '';

        const reader = response.body!.getReader();
        readerRef.current = reader;

        let buffer = '';
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += textDecoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          // Keep the last partial line in the buffer
          buffer = lines.pop() ?? '';

          for (const line of lines) {
            const colonIdx = line.indexOf(':');
            if (colonIdx === -1) continue; // ignore invalid lines

            const field = line.slice(0, colonIdx);
            // strip leading space (SSE spec) and trailing \r (HTTP chunked encoding uses CRLF)
            const value2 = line.slice(colonIdx + 1).replace(/^\s+/, '').replace(/\r$/, '');

            if (field === 'event') {
              eventType = value2;
            } else if (field === 'data') {
              eventData = value2;
            } else if (field === '') {
              // Empty line → SSE event terminator. Always reset after processing.
              if (eventType === 'done') {
                try {
                  const parsed = JSON.parse(eventData) as { traceId?: string; status?: string };
                  if (parsed.status === 'complete') {
                    onDone?.();
                  }
                } catch {
                  // ignore parse errors
                }
              } else if (eventType !== 'trace') {
                // 'message' event: treat as content chunk
                if (eventData) {
                  try {
                    const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
                    if (parsed.type === 'chunk') {
                      accumulatedContentRef.current += parsed.content;
                      onChunk?.(parsed.content);
                    } else if (parsed.type === 'sources') {
                      onSources?.(parsed.sources, parsed.conversationId);
                    }
                  } catch {
                    // Not JSON — treat raw data as a text chunk
                    accumulatedContentRef.current += eventData;
                    onChunk?.(eventData);
                  }
                }
              }
              // Always reset after processing the SSE event terminator
              eventType = 'message';
              eventData = '';
            }
          }
        }

        // Process any remaining buffer after stream ends
        if (buffer.trim()) {
          const colonIdx = buffer.indexOf(':');
          if (colonIdx !== -1) {
            const field = buffer.slice(0, colonIdx);
            const value2 = buffer.slice(colonIdx + 1).replace(/^\s+/, '');
            if (field === 'data' && value2) {
              accumulatedContentRef.current += value2;
              onChunk?.(value2);
            }
          }
        }

        onDone?.();
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Connection error';
        onError?.(message);
      } finally {
        setIsConnected(false);
        readerRef.current = null;
      }
    },
    [close, onChunk, onSources, onError, onDone]
  );

  useEffect(() => {
    return () => close();
  }, [close]);

  return { isConnected, send, close };
}
