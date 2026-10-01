import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import styles from './IconButton.module.css';

export type IconButtonVariant = 'ghost' | 'secondary' | 'danger';
export type IconButtonSize = 32 | 36;

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: IconButtonVariant;
  size?: IconButtonSize;
  /**
   * Required. An icon-only control has no visible text, so the accessible name
   * must be supplied explicitly rather than inferred from a glyph.
   */
  label: string;
  /** Optional native tooltip text; defaults to the accessible label. */
  tooltip?: string;
  children: ReactNode;
}

/**
 * Icon-only command button.
 *
 * The width and height are fixed per size, so a button keeps its footprint when
 * a spinner or focus ring appears. `type` defaults to `button` to prevent
 * accidental form submissions.
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { variant = 'ghost', size = 32, label, tooltip, className, type = 'button', children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      title={tooltip ?? label}
      data-variant={variant}
      data-size={size}
      className={[styles.button, className].filter(Boolean).join(' ')}
      {...rest}
    >
      {children}
    </button>
  );
});
