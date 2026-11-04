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
    const runningVersion = readRunningBuildVersion();
    // Dev server and builds from before this mechanism have no meta tag. There
    // is nothing to compare against, so stay silent rather than guess.
    if (!runningVersion) return;

    let disposed = false;
    let found = false;
    let timer: number | undefined;
    let lastCheckAt = Date.now();
    let inFlight: AbortController | null = null;

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
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (timer !== undefined) window.clearTimeout(timer);
      inFlight?.abort();
    };
  }, []);

  return updateAvailable;
}
