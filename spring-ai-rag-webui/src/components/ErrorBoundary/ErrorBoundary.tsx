import { Component, type ReactNode } from 'react';
import styles from './ErrorBoundary.module.css';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
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
    await fetch('/api/v1/rag/client-errors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } catch {
    // Silently ignore — error reporting must never break the UI
  }
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
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
        <div className={styles.container}>
          <div className={styles.content}>
            <span className={styles.icon}>⚠️</span>
            <h2 className={styles.title}>Something went wrong</h2>
            <p className={styles.message}>
              {this.state.error?.message || 'An unexpected error occurred'}
            </p>
            <button
              className={styles.retryBtn}
              onClick={() => this.setState({ hasError: false, error: undefined })}
            >
              Try Again
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
