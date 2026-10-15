import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Dialog } from '../Dialog';
import { documentProcessingApi, type ProcessingRun } from '../../api/documentProcessing';
import { failureMessage } from '../../utils/failureReason';
import { formatAbsolute } from '../../utils/time';
import { useToast } from '../Toast';
import { QueryErrorBanner } from '../ui';
import styles from './DocumentProcessingRunsModal.module.css';

interface Props {
  documentId: number;
  documentTitle: string;
  onClose: () => void;
}

function active(run: ProcessingRun): boolean {
  return run.status === 'QUEUED' || run.status === 'RUNNING';
}

export function DocumentProcessingRunsModal({ documentId, documentTitle, onClose }: Props) {
  const { t, i18n } = useTranslation();
  const { showToast } = useToast();
  const queryClient = useQueryClient();
  const runsQ = useQuery({
    queryKey: ['document-processing-runs', documentId],
    queryFn: async () => (await documentProcessingApi.list(documentId)).data,
    refetchInterval: query =>
      query.state.data?.items.some(active) ? 2000 : false,
  });
  const actionM = useMutation({
    mutationFn: ({ action, runId }: { action: 'cancel' | 'retry'; runId: string }) =>
      action === 'cancel'
        ? documentProcessingApi.cancel(runId)
        : documentProcessingApi.retry(runId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['document-processing-runs', documentId] });
      queryClient.invalidateQueries({ queryKey: ['documents'] });
    },
    onError: error => {
      showToast(failureMessage(t, 'documents.processingRunsActionFailed', error), 'error');
    },
  });
  const runs = runsQ.data?.items ?? [];
  const error = actionM.error ?? runsQ.error;
  const errorText = error
    ? failureMessage(t, 'documents.processingRunsActionFailed', error)
    : null;
  const hasRuns = useMemo(() => runs.length > 0, [runs.length]);

  return (
    <Dialog
      open
      title={`${t('documents.processingRunsTitle')} — ${documentTitle}`}
      onClose={onClose}
      closeDisabled={actionM.isPending}
      size="large"
    >
      {errorText && <QueryErrorBanner>{errorText}</QueryErrorBanner>}
      {runsQ.isPending && <div className={styles.muted}>{t('common.loading')}</div>}
      {!runsQ.isPending && !runsQ.isError && !hasRuns && (
        <div className={styles.muted}>{t('documents.noProcessingRuns')}</div>
      )}
      {runs.length > 0 && (
        <div className={styles.runs}>
          {runs.map(run => (
            <section className={styles.run} key={run.runId}>
              <div className={styles.runHeader}>
                <div>
                  <strong>{run.status}</strong>
                  <div className={styles.meta}>
                    {t('documents.processingAttempt', {
                      current: run.executionAttempt,
                      max: run.maxAttempts,
                    })}
                    {' · '}
                    {formatAbsolute(run.updatedAt ?? run.createdAt, i18n.language)}
                  </div>
                </div>
                <div className={styles.actions}>
                  {active(run) && (
                    <button
                      type="button"
                      onClick={() => actionM.mutate({ action: 'cancel', runId: run.runId })}
                      disabled={actionM.isPending}
                    >
                      {t('documents.cancelProcessing')}
                    </button>
                  )}
                  {run.status === 'FAILED' || run.status === 'DEAD_LETTERED' ? (
                    <button
                      type="button"
                      onClick={() => actionM.mutate({ action: 'retry', runId: run.runId })}
                      disabled={actionM.isPending}
                    >
                      {t('documents.retryProcessing')}
                    </button>
                  ) : null}
                </div>
              </div>
              {(run.lastError || run.deadLetterReason) && (
                <p className={styles.error}>{run.deadLetterReason ?? run.lastError}</p>
              )}
              <ol className={styles.stages}>
                {run.stages.map(stage => (
                  <li key={`${run.runId}-${stage.stageName}`} className={styles.stage}>
                    <div className={styles.stageHeader}>
                      <span>{stage.stageName}</span>
                      <span>{stage.status}</span>
                    </div>
                    <div className={styles.meta}>
                      {stage.progressLabel ?? `${stage.progressCurrent}/${stage.progressTotal}`}
                      {stage.lastError ? ` · ${stage.lastError}` : ''}
                    </div>
                  </li>
                ))}
              </ol>
            </section>
          ))}
        </div>
      )}
    </Dialog>
  );
}
