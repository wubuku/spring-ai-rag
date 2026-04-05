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

export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
  const { onChunk, onSources, onError, onDone } = options;
  const [isConnected, setIsConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const accumulatedContentRef = useRef<string>('');

  const close = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
      setIsConnected(false);
    }
  }, []);

  const send = useCallback(
    (message: string, collectionId?: number, conversationId?: string) => {
      close();

      const params = new URLSearchParams({
        message,
        collectionId: collectionId?.toString() ?? '',
        conversationId: conversationId ?? '',
      });

      const url = `/api/v1/rag/chat/stream?${params.toString()}`;
      const es = new EventSource(url);
      eventSourceRef.current = es;
      setIsConnected(true);
      accumulatedContentRef.current = '';

      es.onmessage = event => {
        try {
          const data = JSON.parse(event.data) as ChatStreamChunkEvent | ChatStreamSourcesEvent;

          if (data.type === 'chunk') {
            accumulatedContentRef.current += data.content;
            onChunk?.(data.content);
          } else if (data.type === 'sources') {
            onSources?.(data.sources, data.conversationId);
          }
        } catch {
          // Ignore parse errors for empty/invalid messages
        }
      };

      es.onerror = () => {
        const error = 'Connection error';
        onError?.(error);
        close();
      };

      // SSE doesn't have a direct "done" event, but we can detect when the connection closes
      es.addEventListener('done', () => {
        onDone?.();
        close();
      });

      // For SSE, the connection staying open without messages means completion
      // We also listen for the error event which fires on completion
    },
    [close, onChunk, onSources, onError, onDone]
  );

  useEffect(() => {
    return () => close();
  }, [close]);

  return { isConnected, send, close };
}
