/**
 * A counter for work that must not be interrupted by a page reload.
 *
 * The update banner never reloads on its own, but it also should not appear
 * mid-stream: a prompt saying "refresh now" placed next to an answer that is
 * still arriving invites the one action that loses it. Chat streaming and file
 * uploads register here; the banner waits for the counter to reach zero.
 *
 * A counter rather than a boolean because an upload can finish while another
 * turn is still streaming — the last release is what makes the page idle.
 */

type Listener = (busy: boolean) => void;

let activeCount = 0;
const listeners = new Set<Listener>();

function emit(): void {
  const busy = activeCount > 0;
  for (const listener of listeners) {
    listener(busy);
  }
}

/** Mark the start of uninterruptible work; the returned function releases it. */
export function beginBackgroundActivity(): () => void {
  activeCount += 1;
  if (activeCount === 1) emit();

  let released = false;
  return () => {
    if (released) return;
    released = true;
    activeCount -= 1;
    if (activeCount === 0) emit();
  };
}

export function isBackgroundActivityActive(): boolean {
  return activeCount > 0;
}

export function subscribeBackgroundActivity(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
