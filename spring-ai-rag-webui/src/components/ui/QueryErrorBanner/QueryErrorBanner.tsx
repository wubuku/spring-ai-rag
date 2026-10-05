import type { HTMLAttributes, ReactNode } from 'react';
import { Button } from '../../Button';
import { usableReason } from '../../../utils/failureReason';
import styles from './QueryErrorBanner.module.css';

export interface QueryErrorBannerProps extends HTMLAttributes<HTMLDivElement> {
  /**
   * What failed, in the user's terms — "无法加载告警", not "request failed".
   * The call site owns the wording because only it knows what was being read.
   */
  children: ReactNode;
  /**
   * Renders a retry action. Read failures are overwhelmingly transient, and
   * `useQuery` already hands back a `refetch`, so making the caller assemble
   * that button by hand in every file is exactly the duplication this
   * primitive exists to remove.
   */
  onRetry?: () => void;
  retryLabel?: string;
  /**
   * Optional secondary line, typically the thrown error's `message`. Kept out
   * of `children` so the primary sentence stays the only thing a screen reader
   * announces first.
   *
   * Batch 872: filtered through `usableReason` rather than trusted. Every call
   * site hands this primitive whatever `error.message` happens to be — including
   * the transport-level strings that reach the browser when a response carried
   * no reason ("Request failed with status code 404", "Failed to fetch").
   * Printing those under a perfectly good sentence is the noise
   * `src/utils/failureReason.ts` was written to remove, and filtering it here
   * fixes every site at once instead of asking every call site to remember.
   *
   * Batch 909: the counts this paragraph used to give ("twelve call sites",
   * "fifteen call sites" — two different numbers in one file) were removed
   * rather than updated. A count written in prose is a fact that decays: it was
   * already wrong twice by the time anyone noticed, and the next migration makes
   * it wrong again. The mechanism does not decay.
   */
  detail?: string;
}

/**
 * The visible half of "a failed read is not an empty result".
 *
 * Every call site that adopted this had independently reinvented the same
 * three declarations, and `Documents.module.css` had accumulated three
 * conflicting `.error` rules — a centred empty-state style, a tinted panel,
 * and a text colour — all collapsing onto one class name. A shared primitive
 * is the fix; the gate that keeps new call sites honest is
 * `scripts/check-query-errors.mjs`.
 *
 * Batch 909 found the same three-way split still alive on three pages:
 * `Embeddings` and `Evaluation` rendered `role="alert"` divs carrying nothing
 * but a text colour, and `Metrics` reused its *warning* panel. Two of those
 * divs had no `role` at all, so nothing announced them. All three pages now
 * render this primitive, which is the second half of the story: the gate stops
 * a failure from being invisible, and the primitive stops it from looking
 * different on every page.
 *
 * `role="alert"` rather than `role="status"`: this appears without user action
 * and reports a loss of function, which is assertive by the ARIA definition.
 */
export function QueryErrorBanner({
  children,
  onRetry,
  retryLabel,
  detail,
  className,
  ...rest
}: QueryErrorBannerProps) {
  // See the prop's doc: a transport-level string is not a reason, and this
  // primitive is the one place every call site passes through.
  const shownDetail = usableReason(detail);
  return (
    <div
      role="alert"
      className={[styles.banner, className].filter(Boolean).join(' ')}
      {...rest}
    >
      <span className={styles.message}>{children}</span>
      {onRetry && (
        <Button variant="secondary" onClick={onRetry} className={styles.retry}>
          {retryLabel}
        </Button>
      )}
      {shownDetail && <span className={styles.detail}>{shownDetail}</span>}
    </div>
  );
}
