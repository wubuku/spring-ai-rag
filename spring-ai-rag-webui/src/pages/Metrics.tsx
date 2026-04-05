import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { metricsApi } from '../api/metrics';
import { MetricsCharts } from '../components/MetricsCharts';
import styles from './Metrics.module.css';

export function Metrics() {
  const { t } = useTranslation();

  const { data, isPending } = useQuery({
    queryKey: ['metrics'],
    queryFn: () => metricsApi.get(),
    refetchInterval: 30_000,
  });

  return (
    <div>
      <h1 className="page-title">{t('metrics.title')}</h1>
      {isPending ? (
        <div className={styles.loading}>{t('common.loading')}</div>
      ) : data?.data ? (
        <>
          <MetricsCharts data={data.data} />
          <details className={styles.raw}>
            <summary>Raw JSON</summary>
            <pre className={styles.pre}>{JSON.stringify(data.data, null, 2)}</pre>
          </details>
        </>
      ) : (
        <div className={styles.empty}>{t('metrics.noMetrics')}</div>
      )}
    </div>
  );
}
