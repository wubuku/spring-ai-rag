import type { HTMLAttributes, ReactNode } from 'react';
import { Button } from '../../Button';
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
   */
  detail?: string;
}

/**
 * The visible half of "a failed read is not an empty result".
 *
 * Every one of the twelve call sites that adopted this had independently
 * reinvented the same three declarations, and `Documents.module.css` had
 * accumulated three conflicting `.error` rules — a centred empty-state style, a
 * tinted panel, and a text colour — all collapsing onto one class name. A
 * shared primitive is the fix; the gate that keeps new call sites honest is
 * `scripts/check-query-errors.mjs`.
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
      {detail && <span className={styles.detail}>{detail}</span>}
    </div>
  );
}
