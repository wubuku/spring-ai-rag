import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';
import { healthApi } from '../api/health';
import { Skeleton } from '../components/Skeleton';
import { Card } from '../components/Card';
import { QueryErrorBanner } from '../components/ui';
import styles from './Dashboard.module.css';

export function Dashboard() {
  const { t } = useTranslation();

  const { data: health, isPending: healthPending, isError: healthError, refetch: refetchHealth } = useQuery({
    queryKey: ['health'],
    queryFn: () => healthApi.get(),
    refetchInterval: 30_000,
  });

  const { data: docs, isPending: docsPending, isError: docsError, refetch: refetchDocs } = useQuery({
    queryKey: ['documents', 'stats'],
    queryFn: () => documentsApi.list({ page: 0, size: 1 }),
  });

  const {
    data: collections,
    isPending: collectionsPending,
    isError: collectionsError,
    refetch: refetchCollections,
  } = useQuery({
    queryKey: ['collections', 'stats'],
    queryFn: () => collectionsApi.list({ page: 0, size: 1 }),
  });

  const isHealthy = health?.data?.status === 'UP';

  return (
    <div>
      <h1 className="page-title">{t('dashboard.title')}</h1>

      {/* 连不上健康端点和"服务不健康"是两件事。过去两者都渲染
          "系统异常"：偏袒方向没错（绝不误报健康），但把"我不知道"说成
          "它坏了"会让人去查一个根本没坏的数据库。 */}
      {healthError && (
        <QueryErrorBanner onRetry={() => void refetchHealth()} retryLabel={t('common.retry')}>
          {t('dashboard.healthLoadFailed')}
        </QueryErrorBanner>
      )}

      <div className={styles.statusBanner} data-healthy={isHealthy}>
        {healthPending ? (
          <Skeleton width="200px" height="1.5rem" />
        ) : healthError ? (
          <span>{t('dashboard.systemUnreachable')}</span>
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
        <Metric
          label={t('dashboard.documents')}
          failed={docsError}
          pending={docsPending}
          onRetry={() => void refetchDocs()}
          value={docs?.data?.total}
        />
        <Metric
          label={t('dashboard.collections')}
          failed={collectionsError}
          pending={collectionsPending}
          onRetry={() => void refetchCollections()}
          value={collections?.data?.total}
        />
        <Metric
          label={t('dashboard.cache')}
          failed={healthError}
          pending={healthPending}
          onRetry={() => void refetchHealth()}
          value={health?.data?.components?.cache}
        />
        <Metric
          label={t('dashboard.lastCheck')}
          failed={healthError}
          pending={healthPending}
          onRetry={() => void refetchHealth()}
          value={health?.data?.timestamp ? new Date(health.data.timestamp).toLocaleString() : undefined}
        />
      </div>
    </div>
  );
}

/**
 * A single dashboard tile.
 *
 * The old inline `?? '—'` was ambiguous in the worst way: a dash standing for
 * "the server said nothing" looks identical to a dash standing for "we never
 * got an answer", and those two call for opposite responses — one is fine, the
 * other means the page is lying to you. Marking the failed state in the DOM
 * keeps the number honest without turning the whole dashboard into an error
 * page, which is what the `?? '—'` was reaching for.
 */
function Metric({
  label,
  value,
  failed,
  pending,
  onRetry,
}: {
  label: string;
  value: string | number | undefined;
  failed: boolean;
  pending?: boolean;
  onRetry?: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Card>
      {pending ? (
        <Skeleton width="60px" height="2rem" />
      ) : (
        <>
          <div
            className={styles.metric}
            data-unavailable={failed || undefined}
            title={failed ? t('dashboard.metricUnavailable') : undefined}
          >
            {value ?? '—'}
          </div>
          {failed && onRetry && (
            <button type="button" className={styles.metricRetry} onClick={onRetry}>
              {t('common.retry')}
            </button>
          )}
        </>
      )}
      <div className={styles.label}>{label}</div>
    </Card>
  );
}
