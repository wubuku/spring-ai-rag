import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useChatSSE } from './useSSE';
import { clearCredential, setCredential } from '../auth/credentialStore';

describe('useChatSSE', () => {
  const TEST_TURN_ID = '11111111-1111-4111-8111-111111111111';
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
    const normalizedChunks = chunks.map(chunk => addTurnId(chunk));
    const stream = new ReadableStream({
      pull(controller) {
        if (index < normalizedChunks.length) {
          controller.enqueue(new TextEncoder().encode(normalizedChunks[index++]));
        } else {
          controller.close();
        }
      },
    });
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'X-RAG-Turn-Id': TEST_TURN_ID }),
      body: stream,
    });
  };

  const addTurnId = (chunk: string) => {
    let doneEvent = false;
    return chunk.split(/\r?\n/).map(line => {
      if (line.startsWith('event:')) {
        doneEvent = line.slice('event:'.length).trim() === 'done';
      }
      if (doneEvent && line.startsWith('data:')) {
        try {
          const payload = JSON.parse(line.slice('data:'.length).trim());
          if (payload?.status === 'complete' && !payload.turnId) {
            payload.turnId = TEST_TURN_ID;
            return `data: ${JSON.stringify(payload)}`;
          }
        } catch {
          // Multiline/non-JSON data is handled by the parser tests as-is.
        }
      }
      if (line === '') doneEvent = false;
      return line;
    }).join('\n');
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
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
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
      turnId: TEST_TURN_ID,
    });
  });

  it('parses structured CRLF events, multiline data, and an EOF tail block', async () => {
    setupStream([
      'event: content\r\ndata: {"choices":[\r\ndata: {"delta":{"content":"Hello"}}]}\r\n\r\n',
      'event: tool_start\r\ndata: {"tool":"searchKnowledge","toolCallId":"call-1","query":"style"}\r\n\r\n',
      'event: tool_result\r\ndata: {"tool":"searchKnowledge","toolCallId":"call-1","resultCount":2,"elapsedMs":9}\r\n\r\n',
      'event: sources\r\ndata: {"sessionId":"session-1","sources":[{"citationId":"S1","documentId":"doc-1","title":"Guide","chunkText":"Evidence"}]}\r\n\r\n',
      'event: done\r\ndata: {"sessionId":"session-1","status":"complete","metadata":{"context":{"summaryUsed":true}}}',
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
      turnId: TEST_TURN_ID,
      metadata: { context: { summaryUsed: true } },
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
      turnId: TEST_TURN_ID,
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

  it('reuses one idempotency key and turn identity across a bounded retry', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 409,
      headers: new Headers({ 'Retry-After': '0' }),
    });
    setupMockStream();
    const onRetry = vi.fn();
    const onTurnClaimed = vi.fn();
    const { result } = renderHook(() => useChatSSE({
      onRetry,
      onTurnClaimed,
    }));

    await act(async () => {
      result.current.send({ message: 'Retry this turn' });
    });

    expect(mockFetch).toHaveBeenCalledTimes(2);
    const firstRequest = mockFetch.mock.calls[0][1];
    const secondRequest = mockFetch.mock.calls[1][1];
    expect(firstRequest.headers['Idempotency-Key']).toBeTruthy();
    expect(secondRequest.headers['Idempotency-Key'])
      .toBe(firstRequest.headers['Idempotency-Key']);
    expect(secondRequest.body).toBe(firstRequest.body);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onTurnClaimed).toHaveBeenCalledWith(TEST_TURN_ID);
  });

  it('exposes the final HTTP status after a bounded idempotency conflict', async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 409,
      headers: new Headers({ 'Retry-After': '0' }),
    });
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onError }));

    await act(async () => {
      result.current.send({ message: 'Conflict this turn' });
    });

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'HTTP 409',
      expect.objectContaining({ message: 'HTTP 409', status: 409 }),
    ));
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  it('rejects a keyed stream without an immediate valid turn header', async () => {
    const onError = vi.fn();
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers(),
      body: new ReadableStream(),
    });
    const { result } = renderHook(() => useChatSSE({ onError }));

    await act(async () => {
      result.current.send({ message: 'Missing turn identity' });
    });

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'Chat response did not provide a valid turn ID',
      expect.objectContaining({
        message: 'Chat response did not provide a valid turn ID',
      }),
    ));
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
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'X-API-Key': 'root-secret',
        }),
      })
    );
    expect(mockFetch.mock.calls[0][0]).not.toContain('root-secret');
  });

  it('close cancels the reader', async () => {
    const cancel = vi.fn();
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ 'X-RAG-Turn-Id': TEST_TURN_ID }),
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
      headers: new Headers({ 'X-RAG-Turn-Id': TEST_TURN_ID }),
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
        headers: new Headers({ 'X-RAG-Turn-Id': TEST_TURN_ID }),
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
        headers: new Headers({ 'X-RAG-Turn-Id': TEST_TURN_ID }),
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

describe('useChatSSE streaming retries and replay identity', () => {
  const TURN_A = '22222222-2222-4222-8222-222222222222';
  const TURN_B = '33333333-3333-4333-8333-333333333333';
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    clearCredential();
    mockFetch = vi.fn();
    vi.stubGlobal('fetch', mockFetch);
  });

  afterEach(() => {
    clearCredential();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  // 构造带指定 turn id 头的 SSE 流响应；done 载荷需显式携带同一 turnId。
  const streamResponse = (turnId: string, chunks: string[]) => {
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
    return {
      ok: true,
      status: 200,
      headers: new Headers({ 'X-RAG-Turn-Id': turnId }),
      body: stream,
    };
  };

  const doneChunk = (turnId: string) =>
    `event: done\ndata: {"status":"complete","turnId":"${turnId}"}\n\n`;

  it('retries when the response has no body and then streams normally', async () => {
    mockFetch
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ 'X-RAG-Turn-Id': TURN_A }),
      })
      .mockResolvedValueOnce(streamResponse(TURN_A, [doneChunk(TURN_A)]));
    const onRetry = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onRetry, onDone, onError }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('rejects a turn identity change between retry attempts', async () => {
    mockFetch
      .mockResolvedValueOnce(streamResponse(TURN_A, [
        'data: {"type":"chunk","content":"partial"}\n\n',
      ]))
      .mockResolvedValueOnce(streamResponse(TURN_B, [doneChunk(TURN_B)]));
    const onRetry = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onRetry, onDone, onError }));

    await act(async () => {
      result.current.send('Hello');
    });

    // 第一次流在 done 事件前结束（可重试），第二次换了 turn 身份（拒绝）。
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(
      'Chat turn identity changed during retry',
      expect.objectContaining({ message: 'Chat turn identity changed during retry' }),
    );
    expect(onDone).not.toHaveBeenCalled();
  });

  it('rejects a replay whose done event carries a different turn id', async () => {
    mockFetch.mockResolvedValueOnce(streamResponse(TURN_A, [
      doneChunk(TURN_B),
    ]));
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onDone, onError }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(onError).toHaveBeenCalledWith(
      'Chat turn identity changed during replay',
      expect.objectContaining({ message: 'Chat turn identity changed during replay' }),
    );
    expect(onDone).not.toHaveBeenCalled();
  });

  it('waits for the bounded Retry-After duration between attempts', async () => {
    vi.useFakeTimers();
    mockFetch
      .mockResolvedValueOnce({
        ok: false,
        status: 429,
        headers: new Headers({ 'Retry-After': '2' }),
      })
      .mockResolvedValueOnce(streamResponse(TURN_A, [doneChunk(TURN_A)]));
    const onRetry = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onRetry, onDone, onError }));

    let sendPromise!: Promise<void>;
    act(() => {
      sendPromise = Promise.resolve(result.current.send({ message: 'Limited' }));
    });
    // 第一次尝试已失败并进入退避等待。
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(mockFetch).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2500);
    });
    await act(async () => {
      await sendPromise;
    });

    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('retries immediately when Retry-After is not a positive number', async () => {
    mockFetch
      .mockResolvedValueOnce({
        ok: false,
        status: 429,
        headers: new Headers({ 'Retry-After': 'soon' }),
      })
      .mockResolvedValueOnce(streamResponse(TURN_A, [doneChunk(TURN_A)]));
    const onRetry = vi.fn();
    const onDone = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onRetry, onDone }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('skips comment lines, empty blocks and non-JSON events', async () => {
    mockFetch.mockResolvedValueOnce(streamResponse(TURN_A, [
      '\n\ndata: not-json\n\n',
      ': keep-alive\nevent: content\ndata: {"content":"after ping"}\n\n',
      doneChunk(TURN_A),
    ]));
    const onChunk = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useChatSSE({ onChunk, onDone, onError }));

    await act(async () => {
      result.current.send('Hello');
    });

    expect(onChunk).toHaveBeenCalledTimes(1);
    expect(onChunk).toHaveBeenCalledWith('after ping');
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });
});
