import type { HTMLAttributes } from 'react';
import styles from './Card.module.css';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {}

/** Shared card surface: themed background, border and padding. */
export function Card({ className, ...rest }: CardProps) {
  return (
    <div
      className={[styles.card, className].filter(Boolean).join(' ')}
      {...rest}
    />
  );
}
