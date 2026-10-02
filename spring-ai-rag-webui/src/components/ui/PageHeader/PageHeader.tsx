import { useId } from 'react';
import type { ReactNode } from 'react';
import styles from './PageHeader.module.css';

export interface PageHeaderProps {
  /** Page title. Rendered as the single page-level h1. */
  title: ReactNode;
  /** Optional short description, linked to the title for assistive technology. */
  description?: ReactNode;
  /** Control placed before the title, such as a sidebar toggle. */
  leading?: ReactNode;
  /**
   * Page-level commands. The design language allows at most one primary
   * command per view; everything else stays secondary or ghost.
   */
  actions?: ReactNode;
  className?: string;
}

/**
 * Standard page header: title, optional description, and optional commands.
 *
 * Four pages had grown their own header row with different flex rules and
 * different spacing, and the title alone was repeated as a bare
 * `h1.page-title` across the rest of them. Centralising the composition keeps
 * the page hierarchy predictable and gives the description a real relationship
 * to the title instead of leaving it as an unassociated paragraph.
 *
 * <p>As of Batch 805 every protected page routes its title through this
 * component, and the global `.page-title` class is gone.
 * `scripts/check-page-shell.mjs` keeps it that way.
 */
export function PageHeader({
  title,
  description,
  leading,
  actions,
  className,
}: PageHeaderProps) {
  const descriptionId = useId();
  const hasDescription = description !== undefined && description !== null;

  return (
    <div className={[styles.header, className].filter(Boolean).join(' ')}>
      {leading && <div className={styles.leading}>{leading}</div>}
      <div className={styles.titles}>
        <h1 className={styles.title} aria-describedby={hasDescription ? descriptionId : undefined}>
          {title}
        </h1>
        {hasDescription && (
          <p className={styles.description} id={descriptionId}>
            {description}
          </p>
        )}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </div>
  );
}
