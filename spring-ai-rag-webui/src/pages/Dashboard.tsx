import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';
import { healthApi } from '../api/health';
import { Skeleton } from '../components/Skeleton';
import styles from './Dashboard.module.css';

export function Dashboard() {
  const { t } = useTranslation();

  const { data: health, isPending: healthPending } = useQuery({
    queryKey: ['health'],
    queryFn: () => healthApi.get(),
    refetchInterval: 30_000,
  });

  const { data: docs, isPending: docsPending } = useQuery({
    queryKey: ['documents', 'stats'],
    queryFn: () => documentsApi.list({ page: 0, size: 1 }),
  });

  const { data: collections, isPending: collectionsPending } = useQuery({
    queryKey: ['collections', 'stats'],
    queryFn: () => collectionsApi.list({ page: 0, size: 1 }),
  });

  const isHealthy = health?.data?.status === 'UP';

  return (
    <div>
      <h1 className="page-title">{t('dashboard.title')}</h1>

      <div className={styles.statusBanner} data-healthy={isHealthy}>
        {healthPending ? (
          <Skeleton width="200px" height="1.5rem" />
        ) : (
          <>
            <span>
              {isHealthy ? t('dashboard.systemHealthy') : t('dashboard.systemUnhealthy')}
            </span>
            {health?.data && (
              <span className={styles.components}>
                {t('dashboard.db')}: {health.data.components?.database} | {t('dashboard.vector')}:{' '}
                {health.data.components?.pgvector}
              </span>
            )}
          </>
        )}
      </div>

      <div className={styles.grid}>
        <div className={styles.card}>
          {docsPending ? (
            <Skeleton width="60px" height="2rem" />
          ) : (
            <div className={styles.metric}>{docs?.data?.total ?? '—'}</div>
          )}
          <div className={styles.label}>{t('dashboard.documents')}</div>
        </div>
        <div className={styles.card}>
          {collectionsPending ? (
            <Skeleton width="60px" height="2rem" />
          ) : (
            <div className={styles.metric}>{collections?.data?.total ?? '—'}</div>
          )}
          <div className={styles.label}>{t('dashboard.collections')}</div>
        </div>
        <div className={styles.card}>
          {healthPending ? (
            <Skeleton width="60px" height="2rem" />
          ) : (
            <div className={styles.metric}>{health?.data?.components?.cache ?? '—'}</div>
          )}
          <div className={styles.label}>{t('dashboard.cache')}</div>
        </div>
        <div className={styles.card}>
          {healthPending ? (
            <Skeleton width="150px" height="2rem" />
          ) : (
            <div className={styles.metric}>
              {health?.data?.timestamp ? new Date(health.data.timestamp).toLocaleString() : '—'}
            </div>
          )}
          <div className={styles.label}>{t('dashboard.lastCheck')}</div>
        </div>
      </div>
    </div>
  );
}
