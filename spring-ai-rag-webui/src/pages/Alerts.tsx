import { useQuery } from '@tanstack/react-query';
import { alertsApi } from '../api/alerts';
import styles from './Alerts.module.css';

export function Alerts() {
  const { data, isPending } = useQuery({
    queryKey: ['alerts'],
    queryFn: () => alertsApi.listActive(),
    refetchInterval: 30_000,
  });

  return (
    <div>
      <h1 className="page-title">Alerts</h1>
      {isPending ? <div>Loading...</div> : (
        <div className={styles.list}>
          {data?.data?.length === 0 && <div>No active alerts</div>}
          {data?.data?.map((alert) => (
            <div key={alert.id} className={styles.item} data-severity={alert.severity}>
              <div className={styles.header}>
                <span className={styles.name}>{alert.alertName}</span>
                <span className={styles.severity}>{alert.severity}</span>
              </div>
              <div className={styles.message}>{alert.message}</div>
              <div className={styles.time}>
                Triggered: {new Date(alert.triggeredAt).toLocaleString()}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
