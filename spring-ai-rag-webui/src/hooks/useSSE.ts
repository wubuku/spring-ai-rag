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
 *   event: trace\n
 *   data: <traceId>\n
 *   \n
 *   data: <chunk>\n
 *   \n
 *   event: done\n
 *   data: {"status":"complete"}\n
 *   \n
 *
 * Each event is separated by \n\n.
 * Data fields use JSON format: {"type":"chunk","content":"..."}
 * or raw text format.
 */
export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
  const { onChunk, onSources, onError, onDone } = options;
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

        // Read entire stream
        const reader = response.body!.getReader();
        readerRef.current = reader;

        const allChunks: Uint8Array[] = [];
        let readResult: { done: boolean; value?: Uint8Array };
        do {
          readResult = await reader.read();
          if (readResult.value) {
            allChunks.push(readResult.value);
          }
        } while (!readResult.done);

        // Combine all chunks
        let totalLen = 0;
        for (const c of allChunks) totalLen += c.length;
        const combined = new Uint8Array(totalLen);
        let offset = 0;
        for (const c of allChunks) { combined.set(c, offset); offset += c.length; }
        const text = new TextDecoder().decode(combined);

        // SSE parsing using simple regex
        // Split by \n\n to get individual events
        const eventBlocks = text.split('\n\n');

        for (const block of eventBlocks) {
          if (!block.trim()) continue;

          // Extract event type (event: <type>) and data (data: <data>)
          let eventType = 'message';
          let eventData = '';

          // Split block into lines
          const lines = block.split('\n');
          for (const rawLine of lines) {
            const line = rawLine;
            if (line.startsWith('event:')) {
              eventType = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              const dataVal = line.slice(5); // after "data:"
              eventData = eventData + dataVal; // concat multiple data fields
            }
          }

          if (eventType === 'trace') {
            // Ignore trace events
          } else if (eventType === 'done') {
            try {
              const parsed = JSON.parse(eventData) as { status?: string };
              if (parsed.status === 'complete') {
                onDoneRef.current?.();
              }
            } catch { /* ignore */ }
          } else {
            // Content event - try JSON first, then raw text
            if (eventData) {
              let handled = false;
              try {
                const parsed = JSON.parse(eventData) as ChatStreamChunkEvent | ChatStreamSourcesEvent;
                if (parsed.type === 'chunk') {
                  accumulatedContentRef.current += parsed.content;
                  onChunkRef.current?.(parsed.content);
                  handled = true;
                } else if (parsed.type === 'sources') {
                  onSourcesRef.current?.(parsed.sources, parsed.conversationId);
                  handled = true;
                }
              } catch { /* not JSON */ }

              if (!handled) {
                const trimmed = eventData.trim();
                if (trimmed) {
                  accumulatedContentRef.current += trimmed;
                  onChunkRef.current?.(trimmed);
                }
              }
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
