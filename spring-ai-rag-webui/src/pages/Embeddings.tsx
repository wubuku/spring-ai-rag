import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { embeddingsApi } from '../api/embeddings';
import styles from './Evaluation.module.css';

export function Embeddings() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const status = searchParams.get('status') ?? '';
  const collectionKey = searchParams.get('collectionKey') ?? '';
  const batchId = searchParams.get('batchId') ?? '';
  const selectedId = searchParams.get('jobId') ?? '';

  const jobsQ = useQuery({
    queryKey: ['embedding-jobs', status, collectionKey, batchId],
    queryFn: async () =>
      (await embeddingsApi.listJobs({
        page: 0,
        size: 50,
        status: status || undefined,
        collectionKey: collectionKey || undefined,
        batchId: batchId || undefined,
      })).data,
  });

  const readinessQ = useQuery({
    queryKey: ['embedding-readiness', collectionKey],
    queryFn: async () => (await embeddingsApi.readiness(collectionKey)).data,
    enabled: collectionKey.length > 0,
  });

  const detailQ = useQuery({
    queryKey: ['embedding-job', selectedId],
    queryFn: async () => (await embeddingsApi.getJob(selectedId)).data,
    enabled: selectedId.length > 0,
  });

  const cancelM = useMutation({
    mutationFn: (id: string) => embeddingsApi.cancelJob(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['embedding-jobs'] }),
  });
  const retryM = useMutation({
    mutationFn: (id: string) => embeddingsApi.retryJob(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['embedding-jobs'] }),
  });

  const setFilter = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    setSearchParams(next);
  };

  return (
    <div>
      <h1 className="page-title">{t('embeddings.title')}</h1>
      <p className={styles.muted}>{t('embeddings.subtitle')}</p>

      <section className={styles.section} aria-label={t('embeddings.filters')}>
        <div className={styles.form}>
          <label>
            {t('embeddings.status')}
            <input
              value={status}
              onChange={e => setFilter('status', e.target.value)}
              placeholder="QUEUED"
            />
          </label>
          <label>
            {t('embeddings.collectionKey')}
            <input
              value={collectionKey}
              onChange={e => setFilter('collectionKey', e.target.value)}
            />
          </label>
          <label>
            {t('embeddings.batchId')}
            <input
              value={batchId}
              onChange={e => setFilter('batchId', e.target.value)}
            />
          </label>
        </div>
      </section>

      {readinessQ.data && (
        <section className={styles.section} aria-label={t('embeddings.readiness')}>
          <h2>{t('embeddings.readiness')}</h2>
          <div className={styles.cards}>
            {[
              ['enabled', readinessQ.data.enabledDocuments],
              ['fresh', readinessQ.data.freshDocuments],
              ['queued', readinessQ.data.queuedDocuments],
              ['running', readinessQ.data.runningDocuments],
              ['failed', readinessQ.data.failedDocuments],
              ['stale', readinessQ.data.staleOrMissingDocuments],
            ].map(([label, value]) => (
              <div key={String(label)} className={styles.card}>
                <div className={styles.cardLabel}>{t(`embeddings.${label}`)}</div>
                <div className={styles.cardValue}>{String(value)}</div>
              </div>
            ))}
          </div>
        </section>
      )}

      <section className={styles.section} aria-label={t('embeddings.jobs')}>
        {jobsQ.isPending ? (
          <div className={styles.muted}>{t('common.loading')}</div>
        ) : jobsQ.isError ? (
          <div className={styles.error} role="alert">{t('embeddings.loadFailed')}</div>
        ) : !jobsQ.data?.items?.length ? (
          <div className={styles.muted}>{t('embeddings.empty')}</div>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>{t('embeddings.jobId')}</th>
                  <th>{t('embeddings.status')}</th>
                  <th>{t('embeddings.origin')}</th>
                  <th>{t('embeddings.documentId')}</th>
                  <th>{t('embeddings.attempt')}</th>
                  <th>{t('embeddings.progress')}</th>
                  <th>{t('embeddings.error')}</th>
                  <th>{t('embeddings.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {jobsQ.data.items.map(job => (
                  <tr key={job.id}>
                    <td>
                      <button
                        type="button"
                        className={styles.primaryBtn}
                        onClick={() => setFilter('jobId', job.id)}
                      >
                        {job.id.slice(0, 8)}
                      </button>
                    </td>
                    <td>{job.status}</td>
                    <td>{job.origin ?? '—'}</td>
                    <td>{job.documentId ?? '—'}</td>
                    <td>{job.attemptCount}/{job.maxAttempts}</td>
                    <td>{job.progress?.stage ?? '—'}</td>
                    <td className={styles.ellipsis}>{job.lastError ?? '—'}</td>
                    <td>
                      <button type="button" onClick={() => cancelM.mutate(job.id)}>
                        {t('embeddings.cancel')}
                      </button>
                      <button type="button" onClick={() => retryM.mutate(job.id)}>
                        {t('embeddings.retry')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {detailQ.data && (
        <section className={styles.section} aria-label={t('embeddings.detail')}>
          <h2>{t('embeddings.detail')}</h2>
          <pre className={styles.pre}>{JSON.stringify(detailQ.data, null, 2)}</pre>
          <Link to="/documents">{t('embeddings.backToDocuments')}</Link>
        </section>
      )}
    </div>
  );
}
