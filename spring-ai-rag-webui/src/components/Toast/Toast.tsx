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
import { TOAST_ICONS, type ToastType } from './constants';
import { IconButton } from '../ui';
import { X } from 'lucide-react';
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
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const dismissTimersRef = useRef(new Map<string, number>());

  const showToast = useCallback((message: string, type: ToastType = 'info') => {
    const id = crypto.randomUUID();
    setToasts(prev => [...prev, { id, message, type }]);
    const timer = window.setTimeout(() => {
      dismissTimersRef.current.delete(id);
      setToasts(prev => prev.filter(toast => toast.id !== id));
    }, 4000);
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
      <div className={styles.container}>
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
