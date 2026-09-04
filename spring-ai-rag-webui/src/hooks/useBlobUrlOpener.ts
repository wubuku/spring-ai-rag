import { useCallback, useEffect, useRef } from 'react';

/**
 * Opens blob object URLs in a new tab and keeps their delayed revocation
 * timers tracked, so every pending revoke is cancelled when the component
 * unmounts instead of firing against a dead document.
 */
export function useBlobUrlOpener(revokeDelayMs = 60_000) {
  const pendingRevokesRef = useRef<Array<() => void>>([]);

  useEffect(
    () => () => {
      pendingRevokesRef.current.forEach(cancel => cancel());
      pendingRevokesRef.current = [];
    },
    [],
  );

  return useCallback(
    (objectUrl: string) => {
      window.open(objectUrl, '_blank', 'noopener,noreferrer');
      const timerId = window.setTimeout(
        () => URL.revokeObjectURL(objectUrl),
        revokeDelayMs,
      );
      pendingRevokesRef.current.push(() => window.clearTimeout(timerId));
    },
    [revokeDelayMs],
  );
}
