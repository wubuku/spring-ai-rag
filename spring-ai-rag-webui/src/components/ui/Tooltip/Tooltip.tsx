import { cloneElement, isValidElement, useId, useState } from 'react';
import type { KeyboardEvent, ReactElement, ReactNode } from 'react';
import styles from './Tooltip.module.css';

export type TooltipPlacement = 'top' | 'right' | 'bottom' | 'left';

export interface TooltipProps {
  /** Visible on hover and on keyboard focus. */
  content: ReactNode;
  placement?: TooltipPlacement;
  children: ReactElement<{ 'aria-describedby'?: string }>;
}

/**
 * Hover/focus tooltip for unfamiliar icon commands.
 *
 * Deliberately not a container for information the user must read: essential
 * text belongs next to the control, not behind a hover. The trigger keeps its
 * own accessible name; the tooltip only supplements it via `aria-describedby`.
 */
export function Tooltip({ content, placement = 'top', children }: TooltipProps) {
  const tooltipId = useId();
  const [open, setOpen] = useState(false);

  const show = () => setOpen(true);
  const hide = () => setOpen(false);

  const handleKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    // Escape dismisses without moving focus, matching native title tooltips.
    if (event.key === 'Escape') hide();
  };

  if (!isValidElement(children)) {
    throw new Error('Tooltip expects a single React element as its trigger');
  }

  return (
    <span
      className={styles.wrapper}
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
      onKeyDown={handleKeyDown}
    >
      {cloneElement(children, { 'aria-describedby': open ? tooltipId : undefined })}
      <span
        role="tooltip"
        id={tooltipId}
        data-placement={placement}
        data-open={open || undefined}
        className={styles.tooltip}
        // Hidden content must not be reachable by assistive tech while closed.
        aria-hidden={!open || undefined}
      >
        {content}
      </span>
    </span>
  );
}
