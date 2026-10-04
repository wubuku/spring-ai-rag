import {
  useEffect,
  useId,
  useRef,
  type ReactNode,
  type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { IconButton } from '../ui';
import styles from './Dialog.module.css';

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), '
  + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * 打开中的对话框登记表，按打开顺序入栈。
 *
 * 对话框是会叠加的：`Documents` 页在版本历史弹窗里点"恢复版本"会再弹一个
 * 确认框，而底层弹窗并不会因此关闭。逐个实例各挂一个 `document` 监听器时，
 * 一次 Escape 会被**每一个**实例同时处理——用户只想关掉最上面那一个，
 * 结果连底层一起消失，丢失了所在位置。
 *
 * 焦点陷阱同理：底层弹窗的 Tab 处理器会把焦点拽回自己，比顶层更糟。
 * 所以只有**栈顶**处理键盘事件。
 */
const dialogStack: symbol[] = [];

/**
 * body 滚动锁的引用计数。
 *
 * 逐个实例各存各的 `previousOverflow` 会互相覆盖：底层记下 `''`、顶层记下
 * `'hidden'`，两个 cleanup 依次写回后 `overflow` 停在 `'hidden'`——
 * 对话框全关掉了，页面却永久无法滚动，且没有任何可见线索。
 * 引用计数保证只在**第一个**对话框打开时保存、**最后一个**关闭时还原一次。
 */
let scrollLockCount = 0;
let overflowBeforeLock: string | null = null;

function acquireScrollLock() {
  if (scrollLockCount === 0) {
    overflowBeforeLock = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  scrollLockCount += 1;
}

function releaseScrollLock() {
  scrollLockCount = Math.max(0, scrollLockCount - 1);
  if (scrollLockCount === 0 && overflowBeforeLock !== null) {
    document.body.style.overflow = overflowBeforeLock;
    overflowBeforeLock = null;
  }
}

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
  // Batch 856: the close button's accessible name was the literal "Close", and
  // this component had no i18n at all — a screen-reader user on zh-CN heard
  // English for the control that dismisses the dialog. The gate missed it
  // because `label` reaches aria-label through IconButton, not through a literal
  // `aria-label=` attribute; see check-hardcoded-copy.mjs.
  const { t } = useTranslation();
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
    const stackId = Symbol('dialog');
    dialogStack.push(stackId);
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    acquireScrollLock();
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
      // 只有栈顶响应：否则一次 Escape 会关掉整摞对话框，底层弹窗的
      // Tab 处理器还会把焦点从顶层拽走。
      if (dialogStack[dialogStack.length - 1] !== stackId) return;
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
      const index = dialogStack.lastIndexOf(stackId);
      if (index >= 0) dialogStack.splice(index, 1);
      releaseScrollLock();
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
          <IconButton
            // Same stable-hook convention as dialog-backdrop above: the close
            // button is identified by what it does, not by the words on it.
            // Batch 856 moved that label to t('common.close'), and 12 queries
            // across 6 files were matching the English text as if it were an
            // identity. A translated label must not be what a test depends on.
            data-testid="dialog-close"
            onClick={onClose}
            disabled={closeDisabled}
            label={t('common.close')}
            size={32}
          >
            <X size={16} aria-hidden="true" />
          </IconButton>
        </div>
        <div className={styles.body} data-dialog-body>{children}</div>
        {actions && <div className={styles.actions}>{actions}</div>}
      </div>
    </div>,
    document.body,
  );
}
