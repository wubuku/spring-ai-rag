import type { HTMLAttributes, ReactNode } from 'react';
import styles from './StatusBadge.module.css';

export type StatusTone = 'neutral' | 'success' | 'warning' | 'error' | 'info' | 'primary';

export interface StatusBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children'> {
  /**
   * Semantic tone. The design tokens define a complete foreground/background/
   * border triple for every tone, which is what keeps dark mode readable.
   */
  tone?: StatusTone;
  children: ReactNode;
}

/**
 * Status and role badge.
 *
 * Replaces the per-page `.badge` styles that had drifted: two pages defined
 * near-identical base rules, and one page carried two competing visual systems
 * for the same concept.
 *
 * Only a soft treatment exists on purpose. A solid fill would need a readable
 * foreground for every tone, and the status palette does not provide one — a
 * white label on the warning tone measured around 2:1 contrast. Soft badges use
 * the token triples that already exist, so contrast holds in both themes.
 */
export function StatusBadge({ tone = 'neutral', className, children, ...rest }: StatusBadgeProps) {
  return (
    <span
      data-tone={tone}
      className={[styles.badge, className].filter(Boolean).join(' ')}
      {...rest}
    >
      {children}
    </span>
  );
}
