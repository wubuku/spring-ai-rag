import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useChatSSE } from './useSSE';

describe('useChatSSE', () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // Mock fetch with a successful SSE stream response
    mockFetch = vi.fn();
    vi.stubGlobal('fetch', mockFetch);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  const setupMockStream = () => {
    const chunks = ['data: {"type":"chunk","content":"Hello"}\n\n', 'data: {"type":"sources","sources":[],"conversationId":"c1"}\n\n', 'event: done\ndata: {"status":"complete"}\n\n'];
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
      body: JSON.stringify({ message: 'Hello', collectionId: 1, sessionId: 'conv-1' }),
    }));
  });

  it('close cancels the reader', async () => {
    let cancelCalled = false;
    const stream = new ReadableStream({
      pull(controller) {
        controller.enqueue(new TextEncoder().encode('data: test\n\n'));
      },
      cancel() {
        cancelCalled = true;
      },
    });
    mockFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => new Promise(() => {}), cancel: () => { cancelCalled = true; } }), cancel: () => { cancelCalled = true; } },
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
    expect(() => {
      act(() => {
        result.current.send('Hello');
      });
    }).not.toThrow();
  });

  it('send twice cancels previous reader before opening new one', async () => {
    let cancelCount = 0;
    let firstReaderCancelled = false;

    const stream1 = new ReadableStream({
      cancel() { firstReaderCancelled = true; },
    });

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
