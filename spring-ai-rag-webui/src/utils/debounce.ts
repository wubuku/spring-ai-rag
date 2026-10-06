import { useCallback, useEffect, useRef } from 'react';

/** How long the value has to sit still before it is committed. */
export const DEFAULT_COMMIT_DELAY_MS = 250;

export interface DebouncedCommitOptions {
  /**
   * The wait, in milliseconds, before a changed value is committed. Shared by all three
   * call sites so "how long is long enough to be sure typing stopped" is one number.
   */
  delayMs?: number;
  /**
   * Consulted at the moment the timer fires, not when the timer starts. Return `true`
   * to hold the value back — used for an active IME composition, where the characters
   * in the box are not the sentence the user meant yet.
   *
   * A blocked value is **not** dropped: it stays pending, so a later {@link
   * DebouncedCommitHandle.flush} still delivers it. Dropping it is how a search box ends
   * up showing results for text the user never finished typing.
   */
  isBlocked?: () => boolean;
}

export interface DebouncedCommitHandle {
  /**
   * Commit a pending value now instead of waiting.
   *
   * This is the whole reason the hook exists rather than a bare `setTimeout`: blurring
   * out of a search box must apply what is on screen. Without it, a person who types and
   * immediately clicks a row gets a list for the previous query and no indication that
   * the box disagrees with the table.
   *
   * Pass `override` to commit that exact text instead of whatever was last scheduled.
   * The end of an IME composition needs it: the handler has the finished text in its
   * event, but the `setState` carrying it has not rendered yet, so the pending value is
   * the one from before the last few characters were composed — committing that would
   * search for a half-composed syllable and then correct itself 250 ms later with a
   * second request.
   */
  flush: (override?: string) => void;
}

/**
 * Commit a value once it stops changing, and never commit one that is already committed.
 *
 * Why this file exists (Batch 939)
 * -------------------------------
 * Three search boxes in `src/` decided when to act on a keystroke, and all three were
 * defensible on their own, which is what made the divergence invisible:
 *
 *   - `CollectionScopeSelector` waited 250 ms — one request per pause in typing.
 *   - `Documents` and `Files` committed on **every keystroke**. The keyword is part of
 *     the react-query `queryKey`, so typing a six-letter word fired six server requests
 *     and threw away five of them; `Files` additionally called `rememberRoute()` six
 *     times, each one a `localStorage` read, a merge and a write.
 *   - `Search` submits on a form, which is the correct shape for a page whose result is
 *     a full-screen answer.
 *
 * The two live-filtering boxes had IME guards that only committed when
 * `isComposing(event)` was false, which shows the intent was "commit at a meaningful
 * moment", not "commit per key". A debounce is that moment.
 *
 * ## Why the comparison is `value` against `committed`, not against a remembered past
 *
 * The obvious implementation keeps a ref of the last value it committed. That is wrong
 * here, and wrong in a way that shows up as a data bug rather than a crash: the value
 * arrives from outside as often as from the keyboard. Pressing Back moves the URL, the
 * component copies the new keyword into its draft, and a hook that only remembers its
 * own history sees an unfamiliar draft and **writes it straight back to the URL** — and
 * `commitKeyword` deletes `page` on the way, so a Back that landed on page 3 silently
 * lands on page 1.
 *
 * Comparing against the value the rest of the app considers committed makes that
 * impossible: after Back, the draft *is* the committed value, so there is nothing to do.
 * It also removes the mount-time commit, because a draft initialised from the URL is
 * equal to it from the first render.
 *
 * ## What the two arguments are expected to look like
 *
 * Pass the same shape on both sides — all three call sites debounce `draft.trim()`
 * against a `committed` value that is already trimmed, because both ends of a search
 * are trimmed on the way in. A caller that forgets produces a mismatch the write can
 * never close, which costs one redundant commit and then stops: the effect re-arms only
 * when `value` or `committed` changes, and neither does. It is not a loop, but it is one
 * more request than the pause was supposed to cost, which is why the shape is named here
 * rather than left to be discovered.
 */
export function useDebouncedCommit(
  value: string,
  committed: string,
  onCommit: (value: string) => void,
  options: DebouncedCommitOptions = {},
): DebouncedCommitHandle {
  const { delayMs = DEFAULT_COMMIT_DELAY_MS, isBlocked } = options;

  // The newest callback, readable by a timer that was scheduled against an older render.
  // Putting `onCommit` in the dependency array instead would restart the debounce on
  // every render of the host component, which never lets it fire.
  const latestRef = useRef({ onCommit, isBlocked });
  useEffect(() => {
    latestRef.current = { onCommit, isBlocked };
  });

  /** The value a timer is counting down for, or `null` when nothing is outstanding. */
  const pendingRef = useRef<string | null>(null);

  useEffect(() => {
    if (value === committed) {
      pendingRef.current = null;
      return undefined;
    }
    pendingRef.current = value;
    const timer = window.setTimeout(() => {
      // Still outstanding: a later render may have already flushed or replaced it.
      if (pendingRef.current !== value) return;
      if (latestRef.current.isBlocked?.() === true) return;
      pendingRef.current = null;
      latestRef.current.onCommit(value);
    }, delayMs);
    return () => window.clearTimeout(timer);
  }, [value, committed, delayMs]);

  const flush = useCallback((override?: string) => {
    const pending = override ?? pendingRef.current;
    if (pending === null) return;
    if (latestRef.current.isBlocked?.() === true) return;
    pendingRef.current = null;
    latestRef.current.onCommit(pending);
  }, []);

  return { flush };
}
