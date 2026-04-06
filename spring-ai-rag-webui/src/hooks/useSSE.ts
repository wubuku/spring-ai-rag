import { useCallback, useEffect, useRef, useState } from 'react';

export interface ChatStreamChunkEvent {
  type: 'chunk';
  content: string;
}

export interface ChatStreamSourcesEvent {
  type: 'sources';
  sources: unknown[];
  conversationId?: string;
}

export interface UseChatSSEOptions {
  onChunk?: (content: string) => void;
  onSources?: (sources: any[], conversationId?: string) => void;
  onError?: (error: string) => void;
  onDone?: () => void;
}

export interface UseChatSSEReturn {
  isConnected: boolean;
  send: (message: string, collectionId?: number, conversationId?: string) => void;
  close: () => void;
}

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

  /**
   * Process a complete SSE event block immediately.
   * Called as soon as a complete event (ending with \n\n) is detected.
   */
  const processEvent = useCallback((block: string) => {
    if (!block.trim()) return;

    let eventType = 'message';
    let eventData = '';

    // Split block into lines
    const lines = block.split('\n');
    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        const dataVal = line.slice(5); // after "data:"
        eventData = eventData + dataVal;
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

        const reader = response.body!.getReader();
        readerRef.current = reader;

        // Streaming SSE parsing: process events as they arrive
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          // Process complete events (separated by \n\n)
          // Keep the last partial line in the buffer
          while (buffer.includes('\n\n')) {
            const eventEnd = buffer.indexOf('\n\n');
            const eventBlock = buffer.slice(0, eventEnd);
            buffer = buffer.slice(eventEnd + 2); // +2 to skip the \n\n
            processEvent(eventBlock);
          }
        }

        // Process any remaining data in buffer (might be an incomplete last event)
        if (buffer.trim()) {
          // Don't process incomplete events - wait for complete ones
        }

      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : 'Connection error';
        onErrorRef.current?.(errorMessage);
      } finally {
        setIsConnected(false);
        readerRef.current = null;
      }
    },
    [close, processEvent]
  );

  return { isConnected, send, close };
}
