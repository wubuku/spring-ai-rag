import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type CompositionEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { embeddingsApi } from '../api/embeddings';
import type { DerivationRepairPreview } from '../api/embeddings';
import { Dialog } from '../components/Dialog';
import { Card } from '../components/Card';
import { useImeComposition } from '../utils/ime';
import styles from './Embeddings.module.css';

function ImeSafeFilterInput({
  label,
  value,
  placeholder,
  onCommit,
}: {
  label: string;
  value: string;
  placeholder?: string;
  onCommit: (value: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  const ime = useImeComposition();

  useEffect(() => {
    if (!ime.compositionActiveRef.current) {
      setDraft(value);
    }
  }, [ime.compositionActiveRef, value]);

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const next = event.target.value;
    setDraft(next);
    if (!ime.isComposing(event)) {
      onCommit(next);
    }
  };

  const handleCompositionEnd = (
    event: CompositionEvent<HTMLInputElement>,
  ) => {
    ime.handleCompositionEnd();
    const next = event.currentTarget.value;
    setDraft(next);
    onCommit(next);
  };

  return (
    <label>
      {label}
      <input
        aria-label={label}
        value={draft}
        onChange={handleChange}
        onCompositionStart={ime.handleCompositionStart}
        onCompositionEnd={handleCompositionEnd}
        placeholder={placeholder}
      />
    </label>
  );
}

export function Embeddings() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const status = searchParams.get('status') ?? '';
  const collectionKey = searchParams.get('collectionKey') ?? '';
  const batchId = searchParams.get('batchId') ?? '';
  const selectedId = searchParams.get('jobId') ?? '';
  const [repairPreview, setRepairPreview] =
    useState<DerivationRepairPreview | null>(null);
  const repairTriggerRef = useRef<HTMLButtonElement>(null);

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

  const derivationQ = useQuery({
    queryKey: ['derivation-readiness', collectionKey],
    queryFn: async () => (await embeddingsApi.derivationReadiness(collectionKey)).data,
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
  const previewRepairM = useMutation({
    mutationFn: () => embeddingsApi.previewRepair(collectionKey),
    onSuccess: response => setRepairPreview(response.data),
  });
  const applyRepairM = useMutation({
    mutationFn: (preview: DerivationRepairPreview) => embeddingsApi.applyRepair(preview),
    onSuccess: () => {
      setRepairPreview(null);
      qc.invalidateQueries({ queryKey: ['derivation-readiness', collectionKey] });
      qc.invalidateQueries({ queryKey: ['embedding-jobs'] });
    },
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
          <ImeSafeFilterInput
            label={t('embeddings.status')}
            value={status}
            placeholder="QUEUED"
            onCommit={value => setFilter('status', value)}
          />
          <ImeSafeFilterInput
            label={t('embeddings.collectionKey')}
            value={collectionKey}
            onCommit={value => setFilter('collectionKey', value)}
          />
          <ImeSafeFilterInput
            label={t('embeddings.batchId')}
            value={batchId}
            onCommit={value => setFilter('batchId', value)}
          />
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
              <Card key={String(label)}>
                <div className={styles.cardLabel}>{t(`embeddings.${label}`)}</div>
                <div className={styles.cardValue}>{String(value)}</div>
              </Card>
            ))}
          </div>
        </section>
      )}

      {derivationQ.data && (
        <section className={styles.section} aria-label={t('embeddings.derivationIntegrity')}>
          <div className={styles.sectionHeader}>
            <h2>{t('embeddings.derivationIntegrity')}</h2>
            <button
              ref={repairTriggerRef}
              type="button"
              className={styles.primaryBtn}
              disabled={previewRepairM.isPending}
              onClick={() => previewRepairM.mutate()}
            >
              {t('embeddings.previewRepair')}
            </button>
          </div>
          <div className={styles.cards}>
            {[
              ['readyDocuments', derivationQ.data.readyDocuments],
              ['keywordOnlyDocuments', derivationQ.data.keywordOnlyDocuments],
              ['indexingDocuments', derivationQ.data.indexingDocuments],
              ['localUnavailableDocuments', derivationQ.data.localUnavailableDocuments],
              ['corruptDocuments', derivationQ.data.corruptDocuments],
              ['vectorRepairNeededDocuments', derivationQ.data.vectorRepairNeededDocuments],
            ].map(([label, value]) => (
              <Card key={String(label)}>
                <div className={styles.cardLabel}>{t(`embeddings.${label}`)}</div>
                <div className={styles.cardValue}>{String(value)}</div>
              </Card>
            ))}
          </div>
          {previewRepairM.isError && (
            <div className={styles.error} role="alert">
              {t('embeddings.repairFailed')}
            </div>
          )}
        </section>
      )}

      <Dialog
        open={Boolean(repairPreview)}
        title={t('embeddings.repairPreview')}
        onClose={() => setRepairPreview(null)}
        closeDisabled={applyRepairM.isPending}
        returnFocusRef={repairTriggerRef}
        size="large"
        actions={repairPreview ? (
          <>
            <button
              type="button"
              onClick={() => setRepairPreview(null)}
              disabled={applyRepairM.isPending}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className={styles.primaryBtn}
              disabled={applyRepairM.isPending}
              onClick={() => applyRepairM.mutate(repairPreview)}
            >
              {t('embeddings.applyRepair')}
            </button>
          </>
        ) : undefined}
      >
        {repairPreview && (
          <section className={styles.section}>
            <p className={styles.muted}>
              {t('embeddings.repairDocuments', { count: repairPreview.items.length })}
            </p>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>{t('embeddings.documentId')}</th>
                    <th>{t('embeddings.actions')}</th>
                    <th>{t('embeddings.reason')}</th>
                  </tr>
                </thead>
                <tbody>
                  {repairPreview.items.map(item => (
                    <tr key={item.documentId}>
                      <td>{item.documentId}</td>
                      <td>{item.action}</td>
                      <td>{item.reasonCode}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </Dialog>

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
