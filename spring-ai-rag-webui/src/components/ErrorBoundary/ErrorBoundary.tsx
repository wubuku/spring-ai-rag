import { Component, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { getCredentialHeaders } from '../../auth/credentialStore';
import styles from './ErrorBoundary.module.css';
import { TriangleAlert } from 'lucide-react';
import { BASE_URL } from '../../api/client';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
  /**
   * 路由标识。值变化时清除错误态，让崩溃的页面在用户离开后自动恢复，
   * 而不是一直停在错误界面直到手动点"重试"。
   *
   * 用"变化"而不是"相等"来判断：同一个页面重新进入也可能需要重建，
   * 调用方只需要传一个稳定的路径字符串即可。
   */
  resetKey?: unknown;
}

interface State {
  hasError: boolean;
  error?: Error;
}

interface ClientErrorPayload {
  errorType: string;
  errorMessage: string;
  stackTrace?: string;
  componentStack?: string;
  pageUrl: string;
}

/** Gets the current pathname, always returning a string. */
function getCurrentPathname(): string {
  const p: string | null | undefined = window.location.pathname;
  if (p != null && p.length > 0) return p as string;
  return '/';
}

/**
 * Reports client-side errors to the backend server.
 * Errors are sent asynchronously and do not block the UI.
 */
async function reportErrorToServer(payload: ClientErrorPayload): Promise<void> {
  try {
    await fetch(BASE_URL + '/client-errors', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...getCredentialHeaders(),
      },
      body: JSON.stringify(payload),
    });
  } catch {
    // Silently ignore — error reporting must never break the UI
  }
}

/**
 * The last thing a user sees when the app has already broken, and the one
 * screen that was never translated — this is what Batch 808 fixed.
 *
 * A function component rather than inline JSX because the boundary itself has
 * to stay a class (it implements `getDerivedStateFromError`), and class
 * components cannot call `useTranslation`.
 */
function ErrorFallback({ message, onRetry }: { message?: string; onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div className={styles.container}>
      <div className={styles.content}>
        <TriangleAlert className={styles.icon} size={24} aria-hidden="true" />
        <h2 className={styles.title}>{t('common.somethingWentWrong')}</h2>
        <p className={styles.message}>
          {message || t('common.unexpectedError')}
        </p>
        <button className={styles.retryBtn} onClick={onRetry}>
          {t('common.retry')}
        </button>
      </div>
    </div>
  );
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidUpdate(prevProps: Props) {
    // 页面崩溃后用户切到另一个页面：错误来自已经卸载的旧页面，
    // 继续展示旧页面的错误界面会把新页面也一起挡住。
    if (this.state.hasError && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false, error: undefined });
    }
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);

    const payload: ClientErrorPayload = {
      errorType: error.name || 'Error',
      errorMessage: error.message || 'Unknown error',
      stackTrace: error.stack,
      componentStack: errorInfo.componentStack ?? undefined,
      pageUrl: getCurrentPathname(),
    };

    // Report asynchronously — do not await
    reportErrorToServer(payload);
  }

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      return (
        <ErrorFallback
          message={this.state.error?.message}
          onRetry={() => this.setState({ hasError: false, error: undefined })}
        />
      );
    }

    return this.props.children;
  }
}
