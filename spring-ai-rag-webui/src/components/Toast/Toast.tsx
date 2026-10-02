/* eslint-disable react-refresh/only-export-components */
// This file intentionally co-locates useToast hook with ToastProvider component.
// The hook is used by 7+ components (ReembedAllButton, CreateCollectionModal,
// ApiKeys, Files, ABTest, Documents, Collections). Moving to a separate file
// would require updating all consumer import paths unnecessarily.

import {
  useState,
  useCallback,
  useEffect,
  useRef,
  type ReactNode,
} from 'react';
import { ToastContext, useToastContext } from './ToastContext';
import {
  AUTO_DISMISS_TYPES,
  TOAST_AUTO_DISMISS_MS,
  TOAST_ICONS,
  type ToastType,
} from './constants';
import { IconButton } from '../ui';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import styles from './Toast.module.css';

interface ToastItem {
  id: string;
  message: string;
  type: ToastType;
}

export function useToast() {
  return useToastContext();
}

/**
 * The semantic icon for a toast. Kept as its own component because the icon
 * reference is looked up by key inside a `map` callback, where a local `const`
 * is not expressible in JSX.
 */
function ToastIcon({ type }: { type: ToastType }) {
  const Icon = TOAST_ICONS[type];
  return <Icon size={16} aria-hidden="true" />;
}

interface ToastProviderProps {
  children: ReactNode;
}

export function ToastProvider({ children }: ToastProviderProps) {
  const { t } = useTranslation();
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const dismissTimersRef = useRef(new Map<string, number>());

  const showToast = useCallback((message: string, type: ToastType = 'info') => {
    const id = crypto.randomUUID();
    setToasts(prev => [...prev, { id, message, type }]);
    if (!AUTO_DISMISS_TYPES.has(type)) {
      return;
    }
    const timer = window.setTimeout(() => {
      dismissTimersRef.current.delete(id);
      setToasts(prev => prev.filter(toast => toast.id !== id));
    }, TOAST_AUTO_DISMISS_MS);
    dismissTimersRef.current.set(id, timer);
  }, []);

  const removeToast = useCallback((id: string) => {
    const timer = dismissTimersRef.current.get(id);
    if (timer !== undefined) {
      window.clearTimeout(timer);
      dismissTimersRef.current.delete(id);
    }
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  useEffect(() => () => {
    dismissTimersRef.current.forEach(timer => window.clearTimeout(timer));
    dismissTimersRef.current.clear();
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {/*
        live region 必须挂在**先于内容就存在**的容器上。
        之前 `role="status"` / `role="alert"` 挂在每条 toast 自己身上，
        而 toast 是连同内容一起插入 DOM 的——按 ARIA 的 live region 模型，
        屏幕阅读器只播报「已存在的 live region 内部发生的变化」，
        因此这些提示实际上**一条都没有被念出来**（7+ 个消费组件都受影响）。

        这里容器常驻并声明 `aria-live="polite"`，插入即被播报；
        单条 toast 仍保留 role：错误的 `role="alert"` 隐含
        `aria-live="assertive"`，会覆盖容器的 polite，让失败消息打断播报。
      */}
      <div
        className={styles.container}
        data-testid="toast-live-region"
        role="region"
        aria-label={t('common.notifications')}
        aria-live="polite"
        aria-atomic="false"
      >
        {toasts.map(toast => (
          <div
            key={toast.id}
            className={`${styles.toast} ${styles[toast.type]}`}
            role={toast.type === 'error' ? 'alert' : 'status'}
          >
            <span className={styles.icon} data-toast-icon={toast.type}>
              <ToastIcon type={toast.type} />
            </span>
            <span className={styles.message}>{toast.message}</span>
            <IconButton
              className={styles.close}
              onClick={() => removeToast(toast.id)}
              label="Close notification"
              size={32}
            >
              <X size={16} aria-hidden="true" />
            </IconButton>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
