import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useChatSSE } from './useSSE';
import { clearCredential, setCredential } from '../auth/credentialStore';

describe('useChatSSE', () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    clearCredential();
    // Mock fetch with a successful SSE stream response
    mockFetch = vi.fn();
    vi.stubGlobal('fetch', mockFetch);
  });

  afterEach(() => {
    clearCredential();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  const setupMockStream = () => {
    setupStream([
      'data: {"type":"chunk","content":"Hello"}\n\n',
      'data: {"type":"sources","sources":[],"conversationId":"c1"}\n\n',
      'event: done\ndata: {"sessionId":"c1","status":"complete"}\n\n',
    ]);
  };

  const setupStream = (chunks: string[]) => {
    let index = 0;
    const stream = new ReadableStream({
      pull(controller) {
        if (index < chunks.length) {
          controller.enqueue(new TextEncoder().encode(chunks[index++]));
        } else {
          controller.close();
        }
      },
    });
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: stream,
    });
  };

  it('initializes with isConnected false', () => {
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    expect(result.current.isConnected).toBe(false);
  });

  it('send sets isConnected to true and fetches stream', async () => {
    setupMockStream();
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello', 1, 'conv-1');
    });
    await waitFor(() => {
      expect(result.current.isConnected).toBe(false); // disconnected after stream ends
    });
    expect(mockFetch).toHaveBeenCalledWith('/api/v1/rag/chat/stream', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Hello', collectionIds: [1], sessionId: 'conv-1' }),
    }));
  });

  it('returns the server session ID from the done event', async () => {
    setupMockStream();
    const onDone = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onDone }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(onDone).toHaveBeenCalledWith({
      sessionId: 'c1',
      status: 'complete',
    });
  });

  it('parses structured CRLF events, multiline data, and an EOF tail block', async () => {
    setupStream([
      'event: content\r\ndata: {"choices":[\r\ndata: {"delta":{"content":"Hello"}}]}\r\n\r\n',
      'event: tool_start\r\ndata: {"tool":"searchKnowledge","toolCallId":"call-1","query":"style"}\r\n\r\n',
      'event: tool_result\r\ndata: {"tool":"searchKnowledge","toolCallId":"call-1","resultCount":2,"elapsedMs":9}\r\n\r\n',
      'event: sources\r\ndata: {"sessionId":"session-1","sources":[{"citationId":"S1","documentId":"doc-1","title":"Guide","chunkText":"Evidence"}]}\r\n\r\n',
      'event: done\r\ndata: {"sessionId":"session-1","status":"complete"}',
    ]);
    const onChunk = vi.fn();
    const onToolStart = vi.fn();
    const onToolResult = vi.fn();
    const onSources = vi.fn();
    const onDone = vi.fn();
    const { result } = renderHook(() => useChatSSE({
      onChunk,
      onToolStart,
      onToolResult,
      onSources,
      onDone,
    }));

    await act(async () => {
      result.current.send({ message: 'Find evidence', mode: 'AGENT' });
    });

    expect(onChunk).toHaveBeenCalledWith('Hello');
    expect(onToolStart).toHaveBeenCalledWith({
      tool: 'searchKnowledge',
      toolCallId: 'call-1',
      query: 'style',
    });
    expect(onToolResult).toHaveBeenCalledWith({
      tool: 'searchKnowledge',
      toolCallId: 'call-1',
      resultCount: 2,
      elapsedMs: 9,
    });
    expect(onSources).toHaveBeenCalledWith([
      expect.objectContaining({
        citationId: 'S1',
        documentId: 'doc-1',
        title: 'Guide',
      }),
    ], 'session-1');
    expect(onDone).toHaveBeenCalledWith({
      sessionId: 'session-1',
      status: 'complete',
    });
  });

  it('accepts only the first terminal event', async () => {
    setupStream([
      'event: done\ndata: {"sessionId":"session-1","status":"complete"}\n\n',
      'event: done\ndata: {"sessionId":"session-2","status":"complete"}\n\n',
      'event: error\ndata: {"error":{"message":"late error"}}\n\n',
    ]);
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onDone, onError }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledWith({
      sessionId: 'session-1',
      status: 'complete',
    });
    expect(onError).not.toHaveBeenCalled();
  });

  it('maps a structured error and suppresses a later done event', async () => {
    setupStream([
      'event: error\ndata: {"error":{"code":"LLM_UNAVAILABLE","message":"model unavailable"},"traceId":"trace-1","sessionId":"session-1"}\n\n',
      'event: done\ndata: {"sessionId":"session-1","status":"complete"}\n\n',
    ]);
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onDone, onError }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(onError).toHaveBeenCalledWith('model unavailable', {
      message: 'model unavailable',
      code: 'LLM_UNAVAILABLE',
      traceId: 'trace-1',
      sessionId: 'session-1',
    });
    expect(onDone).not.toHaveBeenCalled();
  });

  it('includes the selected model in the request body', async () => {
    setupMockStream();
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );

    await act(async () => {
      result.current.send(
        'Hello',
        [1, 2],
        'conv-1',
        'openrouter/xiaomi/mimo-v2-pro'
      );
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/rag/chat/stream',
      expect.objectContaining({
        body: JSON.stringify({
          message: 'Hello',
          collectionIds: [1, 2],
          sessionId: 'conv-1',
          model: 'openrouter/xiaomi/mimo-v2-pro',
        }),
      })
    );
  });

  it('includes collection keys in the request body', async () => {
    setupMockStream();
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );

    await act(async () => {
      result.current.send(
        'Hello',
        undefined,
        'conv-1',
        undefined,
        ['customer:manual'],
      );
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/rag/chat/stream',
      expect.objectContaining({
        body: JSON.stringify({
          message: 'Hello',
          collectionKeys: ['customer:manual'],
          sessionId: 'conv-1',
        }),
      })
    );
  });

  it('accepts an object request with an explicit collection scope', async () => {
    setupMockStream();
    const { result } = renderHook(() => useChatSSE({}));

    await act(async () => {
      result.current.send({
        message: 'Scoped hello',
        conversationId: 'conv-2',
        model: 'openrouter/model',
        collectionScopeMode: 'SELECTED_COLLECTIONS',
        collectionKeys: ['manual', 'faq'],
      });
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/rag/chat/stream',
      expect.objectContaining({
        body: JSON.stringify({
          message: 'Scoped hello',
          collectionScopeMode: 'SELECTED_COLLECTIONS',
          collectionKeys: ['manual', 'faq'],
          sessionId: 'conv-2',
          model: 'openrouter/model',
        }),
      }),
    );
  });

  it('omits empty collection keys from object requests', async () => {
    setupMockStream();
    const { result } = renderHook(() => useChatSSE({}));

    await act(async () => {
      result.current.send({
        message: 'Visible documents',
        collectionScopeMode: 'CALLER_VISIBLE',
        collectionKeys: [],
      });
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/rag/chat/stream',
      expect.objectContaining({
        body: JSON.stringify({
          message: 'Visible documents',
          collectionScopeMode: 'CALLER_VISIBLE',
        }),
      }),
    );
  });

  it('sends the in-memory credential in a header and never in the URL', async () => {
    setupMockStream();
    setCredential('root-secret');
    const { result } = renderHook(() => useChatSSE({}));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/rag/chat/stream',
      expect.objectContaining({
        headers: {
          'Content-Type': 'application/json',
          'X-API-Key': 'root-secret',
        },
      })
    );
    expect(mockFetch.mock.calls[0][0]).not.toContain('root-secret');
  });

  it('close cancels the reader', async () => {
    const cancel = vi.fn();
    mockFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => new Promise(() => {}),
          cancel,
        }),
      },
    });
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello', 1, 'conv-123');
    });
    // Stream reader is pending, close should cancel it
    await act(async () => {
      result.current.close();
    });
    expect(result.current.isConnected).toBe(false);
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('close is called on unmount when connection is open', async () => {
    let cancelCalled = false;
    mockFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => new Promise(() => {}),
          cancel: () => { cancelCalled = true; },
        }),
        cancel: () => { cancelCalled = true; },
      },
    });
    const { result, unmount } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello', 1, 'conv-123');
    });
    unmount();
    expect(cancelCalled).toBe(true);
  });

  it('send with no optional params does not throw', async () => {
    setupMockStream();
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello');
    });
    // If we reach here without throwing, the test passes
  });

  it('send twice cancels previous reader before opening new one', async () => {
    let cancelCount = 0;

    mockFetch
      .mockResolvedValueOnce({
        ok: true,
        body: {
          getReader: () => ({
            read: () => new Promise(() => {}), // never resolves, simulating pending stream
            cancel: () => { cancelCount++; },
          }),
          cancel: () => { cancelCount++; },
        },
      })
      .mockResolvedValueOnce({
        ok: true,
        body: {
          getReader: () => ({
            read: () => new Promise(() => {}),
            cancel: () => {},
          }),
          cancel: () => {},
        },
      });

    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );

    await act(async () => {
      result.current.send('Hello 1', 1, 'conv-1');
    });

    // First reader should be cancelled when second send is called
    await act(async () => {
      result.current.send('Hello 2', 2, 'conv-2');
    });

    // First reader's cancel should have been called
    expect(cancelCount).toBeGreaterThanOrEqual(1);
  });
});
