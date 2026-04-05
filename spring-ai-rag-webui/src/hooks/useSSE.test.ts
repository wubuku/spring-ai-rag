import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useChatSSE } from './useSSE';

describe('useChatSSE', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('initializes with isConnected false', async () => {
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    expect(result.current.isConnected).toBe(false);
  });

  it('send creates EventSource and sets isConnected to true', async () => {
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello', 1, 'conv-123');
    });
    await waitFor(() => {
      expect(result.current.isConnected).toBe(true);
    });
  });

  it('close sets isConnected to false', async () => {
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello', 1, 'conv-123');
    });
    await waitFor(() => expect(result.current.isConnected).toBe(true));

    await act(async () => {
      result.current.close();
    });
    expect(result.current.isConnected).toBe(false);
  });

  it('close is called on unmount when connection is open', async () => {
    const closeSpy = vi.spyOn(EventSource.prototype, 'close');
    const { result, unmount } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    await act(async () => {
      result.current.send('Hello', 1, 'conv-123');
    });
    await waitFor(() => expect(result.current.isConnected).toBe(true));

    unmount();
    expect(closeSpy).toHaveBeenCalled();
    closeSpy.mockRestore();
  });

  it('send with no optional params does not throw', async () => {
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );
    expect(() => {
      act(() => {
        result.current.send('Hello');
      });
    }).not.toThrow();
  });

  it('send twice closes previous connection before opening new one', async () => {
    const closeSpy = vi.spyOn(EventSource.prototype, 'close');
    const { result } = renderHook(() =>
      useChatSSE({ onChunk: vi.fn(), onSources: vi.fn(), onError: vi.fn(), onDone: vi.fn() })
    );

    await act(async () => {
      result.current.send('Hello 1', 1, 'conv-1');
    });
    await waitFor(() => expect(result.current.isConnected).toBe(true));

    await act(async () => {
      result.current.send('Hello 2', 2, 'conv-2');
    });

    // First connection should have been closed when second send is called
    expect(closeSpy).toHaveBeenCalledTimes(1);
    closeSpy.mockRestore();
  });
});
