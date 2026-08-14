import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useApiKeyAuth } from '../auth/ApiKeyAuthContext';
import styles from './Unlock.module.css';

interface UnlockLocationState {
  from?: string;
}

export function Unlock() {
  const { t } = useTranslation();
  const { isUnlocked, unlock } = useApiKeyAuth();
  const [wasUnlockedOnEntry] = useState(isUnlocked);
  const location = useLocation();
  const navigate = useNavigate();
  const [rootApiKey, setRootApiKey] = useState('');
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  if (wasUnlockedOnEntry) {
    return <Navigate to="/dashboard" replace />;
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setIsSubmitting(true);
    try {
      await unlock(rootApiKey);
      setRootApiKey('');
      const state = location.state as UnlockLocationState | null;
      navigate(state?.from || '/dashboard', { replace: true });
    } catch {
      setError(t('unlock.invalidKey'));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <main className={styles.page}>
      <section className={styles.panel} aria-labelledby="unlock-title">
        <div className={styles.brand}>spring-ai-rag</div>
        <h1 id="unlock-title" className={styles.title}>{t('unlock.title')}</h1>
        <form onSubmit={handleSubmit} className={styles.form}>
          <label htmlFor="root-api-key" className={styles.label}>
            {t('unlock.rootApiKey')}
          </label>
          <input
            id="root-api-key"
            data-testid="root-api-key"
            type="password"
            value={rootApiKey}
            onChange={event => setRootApiKey(event.target.value)}
            autoComplete="off"
            autoFocus
            className={styles.input}
            aria-invalid={Boolean(error)}
          />
          {error && <div className={styles.error} role="alert">{error}</div>}
          <button
            type="submit"
            className={styles.submit}
            disabled={isSubmitting || !rootApiKey.trim()}
          >
            {isSubmitting ? t('unlock.unlocking') : t('unlock.submit')}
          </button>
        </form>
        <p className={styles.securityNote}>{t('unlock.memoryOnly')}</p>
      </section>
    </main>
  );
}
