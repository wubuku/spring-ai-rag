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
  const onChunkRef = useRef(onChunk);
  const onSourcesRef = useRef(onSources);
  const onErrorRef = useRef(onError);
  const onDoneRef = useRef(onDone);

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
      let chunkCount = 0;
      let emitCount = 0;
      let lastLine = '';

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

          chunkCount++;
          const text = textDecoder.decode(value, { stream: true });
          buffer += text;

          // Debug: log chunk info every 5 chunks
          if (chunkCount <= 3 || chunkCount % 10 === 0) {
            console.log(`[SSE] Chunk ${chunkCount}: ${JSON.stringify(text.slice(0, 50))}... buffer len=${buffer.length}`);
          }

          // Process complete lines
          while (buffer.includes('\n')) {
            const newlineIdx = buffer.indexOf('\n');
            const line = buffer.slice(0, newlineIdx);
            buffer = buffer.slice(newlineIdx + 1);
            lastLine = line;

            const colonIdx = line.indexOf(':');
            if (colonIdx === -1) {
              // Empty line → SSE event terminator
              if (eventData) {
                console.log(`[SSE] Empty line, emitting: ${JSON.stringify(eventData)} (eventType=${eventType})`);
                emitCount++;
                if (eventType === 'done') {
                  try {
                    const parsed = JSON.parse(eventData) as { traceId?: string; status?: string };
                    console.log(`[SSE] Done event parsed: ${JSON.stringify(parsed)}`);
                    if (parsed.status === 'complete') {
                      onDoneRef.current?.();
                      console.log(`[SSE] onDone called!`);
                    }
                  } catch (e) {
                    console.log(`[SSE] Done parse error: ${e}`);
                  }
                } else if (eventType !== 'trace') {
                  try {
                    const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
                    if (parsed.type === 'chunk') {
                      console.log(`[SSE] Chunk event: ${JSON.stringify(parsed.content)}`);
                      accumulatedContentRef.current += parsed.content;
                      onChunkRef.current?.(parsed.content);
                    } else if (parsed.type === 'sources') {
                      onSourcesRef.current?.(parsed.sources, parsed.conversationId);
                    }
                  } catch (e) {
                    // Not JSON - treat as raw text
                    console.log(`[SSE] Raw text chunk: ${JSON.stringify(eventData)}`);
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
            const value2 = line.slice(colonIdx + 1).replace(/^\s+/, '').replace(/\r$/, '');

            if (field === 'event') {
              console.log(`[SSE] Event type: ${value2}, pending eventData=${JSON.stringify(eventData)}`);
              if (eventData) {
                console.log(`[SSE] WARNING: had pending eventData=${JSON.stringify(eventData)}, should have emitted!`);
              }
              eventType = value2;
              eventData = '';
            } else if (field === 'data') {
              console.log(`[SSE] Data field: ${JSON.stringify(value2)}, current eventData=${JSON.stringify(eventData)}, eventType=${eventType}`);
              if (eventData) {
                // This shouldn't happen in well-formed SSE
                console.log(`[SSE] WARNING: overwriting pending eventData=${JSON.stringify(eventData)}`);
              }
              eventData = value2;
            }
          }
        }

        console.log(`[SSE] Stream ended. chunkCount=${chunkCount}, emitCount=${emitCount}, buffer=${JSON.stringify(buffer)}, lastLine=${JSON.stringify(lastLine)}, eventData=${JSON.stringify(eventData)}, eventType=${eventType}`);

        // Process remaining buffer
        if (buffer.trim() || eventData) {
          console.log(`[SSE] Processing remaining: buffer=${JSON.stringify(buffer)}, eventData=${JSON.stringify(eventData)}`);
          if (eventData && eventType !== 'trace') {
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
        console.log(`[SSE] Error: ${errorMessage}`);
        onErrorRef.current?.(errorMessage);
      } finally {
        console.log(`[SSE] Finally: isConnected=false, accumulated=${JSON.stringify(accumulatedContentRef.current)}`);
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
