import { useEffect, useState } from 'react';
import { fetchServedBuildVersion, readRunningBuildVersion } from '../utils/buildVersion';
import {
  BUILD_VERSION_POLL_INTERVAL_MS,
  BUILD_VERSION_REVISIT_MIN_GAP_MS,
} from '../utils/timing';

/**
 * Whether the server has a newer WebUI build than the one this tab is running.
 *
 * No check runs at mount: the tab already is the current build at that moment,
 * so an immediate request would spend first-paint budget asking a question whose
 * answer is "same". The first check happens one poll interval later, or when the
 * user returns to a tab that has been in the background long enough for a release
 * to have landed.
 *
 * Every failure (offline, 404, timeout, malformed response) resolves to "no
 * news"; only a readable, different version turns the flag on. Once it is on the
 * polling stops — repeating a question the user has not acted on adds requests
 * without adding information.
 */
export function useBuildVersionUpdate(): boolean {
  const [updateAvailable, setUpdateAvailable] = useState(false);

  useEffect(() => {
    let disposed = false;
    let found = false;
    let timer: number | undefined;
    let lastCheckAt = Date.now();
    let inFlight: AbortController | null = null;

    const markUpdateAvailable = () => {
      if (disposed || found) return;
      found = true;
      if (timer !== undefined) {
        window.clearTimeout(timer);
        timer = undefined;
      }
      inFlight?.abort();
      setUpdateAvailable(true);
    };

    // 升级后旧标签页懒加载已删除的 hash chunk 会得到 404，页面本身还活着，
    // 但后续路由会白屏。Vite 在动态 import 失败时派发 `vite:preloadError`；
    // 各引擎对同一失败的错误文案不同，所以同时兜住 window error 与
    // unhandledrejection。发现缺失 chunk 立即显示“立即刷新”，不等 5 分钟轮询。
    const isChunkLoadFailure = (message: unknown): boolean => {
      if (typeof message !== 'string') return false;
      return /failed to fetch dynamically imported module|error loading dynamically imported module|importing a module script failed|failed to load module script|chunkloaderror/i
        .test(message);
    };
    const handlePreloadError = () => markUpdateAvailable();
    const handleWindowError = (event: ErrorEvent) => {
      if (isChunkLoadFailure(event.message)) markUpdateAvailable();
    };
    const handleUnhandledRejection = (event: PromiseRejectionEvent) => {
      const reason = event.reason as { message?: unknown } | undefined;
      if (isChunkLoadFailure(reason?.message)) markUpdateAvailable();
    };

    window.addEventListener('vite:preloadError', handlePreloadError);
    window.addEventListener('error', handleWindowError);
    window.addEventListener('unhandledrejection', handleUnhandledRejection);

    const runningVersion = readRunningBuildVersion();
    // Dev server and builds from before this mechanism have no meta tag. There
    // is nothing to compare against, so stay silent rather than guess.
    if (!runningVersion) {
      return () => {
        disposed = true;
        window.removeEventListener('vite:preloadError', handlePreloadError);
        window.removeEventListener('error', handleWindowError);
        window.removeEventListener('unhandledrejection', handleUnhandledRejection);
      };
    }

    const schedule = () => {
      if (disposed || found) return;
      timer = window.setTimeout(() => {
        void check();
      }, BUILD_VERSION_POLL_INTERVAL_MS);
    };

    const check = async () => {
      timer = undefined;
      if (disposed || found) return;

      if (document.visibilityState !== 'visible') {
        schedule();
        return;
      }

      lastCheckAt = Date.now();
      inFlight?.abort();
      const controller = new AbortController();
      inFlight = controller;

      const servedVersion = await fetchServedBuildVersion(controller.signal);
      if (disposed || found) return;

      if (servedVersion && servedVersion !== runningVersion) {
        found = true;
        setUpdateAvailable(true);
        return;
      }
      schedule();
    };

    const handleVisibilityChange = () => {
      if (disposed || found || document.visibilityState !== 'visible') return;
      if (Date.now() - lastCheckAt < BUILD_VERSION_REVISIT_MIN_GAP_MS) return;
      if (timer !== undefined) {
        window.clearTimeout(timer);
        timer = undefined;
      }
      void check();
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    schedule();

    return () => {
      disposed = true;
      window.removeEventListener('vite:preloadError', handlePreloadError);
      window.removeEventListener('error', handleWindowError);
      window.removeEventListener('unhandledrejection', handleUnhandledRejection);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (timer !== undefined) window.clearTimeout(timer);
      inFlight?.abort();
    };
  }, []);

  return updateAvailable;
}
