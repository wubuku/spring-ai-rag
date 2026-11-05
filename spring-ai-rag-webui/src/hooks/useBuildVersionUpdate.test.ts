import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useBuildVersionUpdate } from './useBuildVersionUpdate';
import {
  BUILD_VERSION_POLL_INTERVAL_MS,
  BUILD_VERSION_REVISIT_MIN_GAP_MS,
} from '../utils/timing';

function servedIndex(version: string): string {
  return `<html><head><meta name="webui-build-version" content="${version}"></head></html>`;
}

function mockFetchVersion(version: string) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    text: async () => servedIndex(version),
  }));
}

function setRunningVersion(version: string | null) {
  document.head.innerHTML = version
    ? `<meta name="webui-build-version" content="${version}">`
    : '';
}

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value: state,
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  setVisibility('visible');
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.head.innerHTML = '';
});

describe('useBuildVersionUpdate', () => {
  it('does nothing at mount and stays silent when the build is unchanged', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-1');

    const { result } = renderHook(() => useBuildVersionUpdate());
    expect(result.current).toBe(false);
    // No check spends first-paint budget: the first request waits one interval.
    expect(fetch).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(false);
  });

  it('reports an update once the served build differs', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-2');

    const { result } = renderHook(() => useBuildVersionUpdate());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(result.current).toBe(true);

    // The question is settled; polling stops instead of repeating it.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('stays silent when the check cannot answer (offline, 404)', async () => {
    setRunningVersion('build-1');
    const offlineFetch = vi.fn().mockRejectedValue(new Error('offline'));
    vi.stubGlobal('fetch', offlineFetch);

    const { result } = renderHook(() => useBuildVersionUpdate());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(offlineFetch).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(false);

    // A failed check is not terminal: the next interval tries again.
    const notFoundFetch = vi.fn().mockResolvedValue({ ok: false, text: async () => '' });
    vi.stubGlobal('fetch', notFoundFetch);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(notFoundFetch).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(false);
  });

  it('shows the refresh banner immediately when a lazy chunk is missing', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-1');

    const { result } = renderHook(() => useBuildVersionUpdate());
    expect(result.current).toBe(false);

    act(() => {
      window.dispatchEvent(new Event('vite:preloadError'));
    });
    expect(result.current).toBe(true);
    // 已经知道要刷新，轮询应停止而不是继续占用请求。
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('recognizes engine-specific dynamic import error messages', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-1');

    const { result } = renderHook(() => useBuildVersionUpdate());
    act(() => {
      window.dispatchEvent(new ErrorEvent('error', {
        message: 'Importing a module script failed.',
      }));
    });
    expect(result.current).toBe(true);
  });

  it('disables itself when the document has no build identity', async () => {
    setRunningVersion(null);
    mockFetchVersion('build-2');

    const { result } = renderHook(() => useBuildVersionUpdate());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(fetch).not.toHaveBeenCalled();
    expect(result.current).toBe(false);
  });

  it('checks when a returning tab has been away longer than the floor', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-2');

    const { result } = renderHook(() => useBuildVersionUpdate());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_REVISIT_MIN_GAP_MS + 1_000);
    });
    setVisibility('visible');
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
      await Promise.resolve();
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(true);
  });

  it('does not check on a tab switch inside the minimum gap', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-2');

    renderHook(() => useBuildVersionUpdate());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetch).not.toHaveBeenCalled();
  });

  it('skips a scheduled check while the tab is hidden and tries next interval', async () => {
    setRunningVersion('build-1');
    mockFetchVersion('build-2');
    setVisibility('hidden');

    const { result } = renderHook(() => useBuildVersionUpdate());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(fetch).not.toHaveBeenCalled();

    setVisibility('visible');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BUILD_VERSION_POLL_INTERVAL_MS);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(true);
  });
});
