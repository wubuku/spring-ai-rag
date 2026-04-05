import { useQuery } from '@tanstack/react-query';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';
import { healthApi } from '../api/health';
import styles from './Dashboard.module.css';

export function Dashboard() {
  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: () => healthApi.get(),
    refetchInterval: 30_000,
  });

  const { data: docs } = useQuery({
    queryKey: ['documents', 'stats'],
    queryFn: () => documentsApi.list({ page: 0, size: 1 }),
  });

  const { data: collections } = useQuery({
    queryKey: ['collections', 'stats'],
    queryFn: () => collectionsApi.list({ page: 0, size: 1 }),
  });

  const isHealthy = health?.data?.status === 'UP';

  return (
    <div>
      <h1 className="page-title">Dashboard</h1>

      <div className={styles.statusBanner} data-healthy={isHealthy}>
        <span>{isHealthy ? '✅ System Healthy' : '❌ System Unhealthy'}</span>
        {health?.data && (
          <span className={styles.components}>
            DB: {health.data.components?.database} | Vector: {health.data.components?.pgvector}
          </span>
        )}
      </div>

      <div className={styles.grid}>
        <div className={styles.card}>
          <div className={styles.metric}>{docs?.data?.total ?? '—'}</div>
          <div className={styles.label}>Documents</div>
        </div>
        <div className={styles.card}>
          <div className={styles.metric}>{collections?.data?.total ?? '—'}</div>
          <div className={styles.label}>Collections</div>
        </div>
        <div className={styles.card}>
          <div className={styles.metric}>{health?.data?.components?.cache ?? '—'}</div>
          <div className={styles.label}>Cache</div>
        </div>
        <div className={styles.card}>
          <div className={styles.metric}>
            {health?.data?.timestamp ? new Date(health.data.timestamp).toLocaleString() : '—'}
          </div>
          <div className={styles.label}>Last Check</div>
        </div>
      </div>
    </div>
  );
}
