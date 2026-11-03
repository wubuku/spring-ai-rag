import type { ReactNode } from 'react';
import styles from './Pager.module.css';

export interface PagerProps {
  /** Zero-based page index. */
  page: number;
  hasNext: boolean;
  onChange: (page: number) => void;
  summary: ReactNode;
  previousLabel: string;
  nextLabel: string;
  /** Accessible name for the navigation landmark. */
  label: string;
}

/**
 * Shared prev/next control for server-paginated lists.
 *
 * Collections and evaluation history used to fetch only page 0 with no way
 * forward; at scale the rest of the data was simply unreachable. The document
 * table already had this markup, so the shape is reused rather than invented.
 */
export function Pager({
  page,
  hasNext,
  onChange,
  summary,
  previousLabel,
  nextLabel,
  label,
}: PagerProps) {
  return (
    <nav className={styles.pager} aria-label={label}>
      <button
        type="button"
        onClick={() => onChange(page - 1)}
        disabled={page === 0}
        className={styles.pageBtn}
      >
        {previousLabel}
      </button>
      <span className={styles.pageInfo} aria-live="polite">{summary}</span>
      <button
        type="button"
        onClick={() => onChange(page + 1)}
        disabled={!hasNext}
        className={styles.pageBtn}
      >
        {nextLabel}
      </button>
    </nav>
  );
}
