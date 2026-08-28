import {
  useEffect,
  useId,
  useRef,
  type ReactNode,
  type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import styles from './Dialog.module.css';

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), '
  + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface DialogProps {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  actions?: ReactNode;
  onClose: () => void;
  closeDisabled?: boolean;
  size?: 'small' | 'medium' | 'large';
  initialFocusRef?: RefObject<HTMLElement | null>;
  returnFocusRef?: RefObject<HTMLElement | null>;
  ariaLabel?: string;
}

export function Dialog({
  open,
  title,
  description,
  children,
  actions,
  onClose,
  closeDisabled = false,
  size = 'medium',
  initialFocusRef,
  returnFocusRef,
  ariaLabel,
}: DialogProps) {
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  const closeDisabledRef = useRef(closeDisabled);
  onCloseRef.current = onClose;
  closeDisabledRef.current = closeDisabled;

  useEffect(() => {
    if (!open) return;
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const panel = panelRef.current;
    const target = initialFocusRef?.current
      ?? panel?.querySelector<HTMLElement>('[autofocus]')
      ?? panel?.querySelector<HTMLElement>(`[data-dialog-body] ${FOCUSABLE}`)
      ?? panel?.querySelector<HTMLElement>(FOCUSABLE)
      ?? panel;
    target?.focus();
    const restoreFocus = () => {
      const returnTarget = returnFocusRef?.current ?? previousFocusRef.current;
      if (returnTarget?.isConnected) {
        returnTarget.focus();
      }
    };

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !closeDisabledRef.current) {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (focusable.length === 0) {
        event.preventDefault();
        panel.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = previousOverflow;
      restoreFocus();
    };
  }, [initialFocusRef, open, returnFocusRef]);

  if (!open) return null;

  return createPortal(
    <div
      className={styles.backdrop}
      data-testid="dialog-backdrop"
      onMouseDown={event => {
        if (event.target === event.currentTarget && !closeDisabled) onClose();
      }}
    >
      <div
        ref={panelRef}
        className={styles.panel}
        data-size={size}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabel ? undefined : titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
      >
        <div className={styles.header}>
          <div>
            <h2 className={styles.title} id={titleId}>{title}</h2>
            {description && (
              <div className={styles.description} id={descriptionId}>
                {description}
              </div>
            )}
          </div>
          <button
            type="button"
            className={styles.close}
            onClick={onClose}
            disabled={closeDisabled}
            aria-label="Close"
          >
            ×
          </button>
        </div>
        <div className={styles.body} data-dialog-body>{children}</div>
        {actions && <div className={styles.actions}>{actions}</div>}
      </div>
    </div>,
    document.body,
  );
}
