import styles from './Skeleton.module.css';

interface SkeletonProps {
  width?: string;
  height?: string;
  borderRadius?: string;
  className?: string;
}

export function Skeleton({ width = '100%', height = '1rem', borderRadius = '4px', className }: SkeletonProps) {
  return (
    <div
      className={`${styles.skeleton} ${className ?? ''}`}
      style={{ width, height, borderRadius }}
    />
  );
}

export function SkeletonText({ lines = 3, lastLineWidth = '60%' }: { lines?: number; lastLineWidth?: string }) {
  return (
    <div className={styles.textContainer}>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton
          key={i}
          width={i === lines - 1 ? lastLineWidth : '100%'}
          height="0.875rem"
        />
      ))}
    </div>
  );
}

export function SkeletonCard() {
  return (
    <div className={styles.card}>
      <Skeleton width="40%" height="1.25rem" />
      <SkeletonText lines={2} />
      <Skeleton width="30%" height="0.75rem" />
    </div>
  );
}

export function SkeletonTable({ rows = 5 }: { rows?: number }) {
  return (
    <div className={styles.table}>
      <div className={styles.tableHeader}>
        <Skeleton width="15%" height="0.875rem" />
        <Skeleton width="30%" height="0.875rem" />
        <Skeleton width="15%" height="0.875rem" />
        <Skeleton width="20%" height="0.875rem" />
        <Skeleton width="10%" height="0.875rem" />
      </div>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className={styles.tableRow}>
          <Skeleton width="15%" height="0.875rem" />
          <Skeleton width="30%" height="0.875rem" />
          <Skeleton width="15%" height="0.875rem" />
          <Skeleton width="20%" height="0.875rem" />
          <Skeleton width="10%" height="0.875rem" />
        </div>
      ))}
    </div>
  );
}
