import { useQuery } from '@tanstack/react-query';
import { metricsApi } from '../api/metrics';
import styles from './Metrics.module.css';

export function Metrics() {
  const { data, isPending } = useQuery({
    queryKey: ['metrics'],
    queryFn: () => metricsApi.get(),
    refetchInterval: 30_000,
  });

  return (
    <div>
      <h1 className="page-title">Metrics</h1>
      {isPending ? (
        <div>Loading...</div>
      ) : (
        <pre className={styles.pre}>{JSON.stringify(data?.data, null, 2)}</pre>
      )}
    </div>
  );
}
