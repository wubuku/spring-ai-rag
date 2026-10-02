import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  collectionsApi,
  type Collection,
  type CollectionPurgePreview,
  type CollectionPurgeResult,
} from '../api/collections';
import { useApiKeyAuth } from '../auth/ApiKeyAuthContext';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { CreateCollectionModal } from '../components/CreateCollectionModal';
import { Dialog } from '../components/Dialog';
import { Card } from '../components/Card';
import styles from './Collections.module.css';
import { EmptyState, PageHeader, QueryErrorBanner } from '../components/ui';

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function Collections() {
  const { t } = useTranslation();
  const [page] = useState(0);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [purgeTarget, setPurgeTarget] = useState<Collection | null>(null);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const { identity } = useApiKeyAuth();

  const { data, isPending, isError, refetch } = useQuery({
    queryKey: ['collections', page],
    queryFn: () => collectionsApi.list({ page, size: 20 }),
  });

  // Fail-closed: capabilityData 失败时 purgeVisible 保持 false，破坏性操作被
  // 隐藏而不是被误展示。但"按钮不见了"本身也需要一句解释，否则用户会以为
  // 权限被降级或功能被移除。
  const {
    data: capabilityData,
    isError: capabilityError,
    refetch: refetchCapability,
  } = useQuery({
    queryKey: ['integration-capabilities'],
    queryFn: collectionsApi.integrationCapabilities,
    enabled: identity?.principalType === 'ENVIRONMENT_ROOT',
    staleTime: 30_000,
  });

  // Every step is optional: the leading `?.` alone guards `capabilityData`, and
  // a 200 carrying an unexpected envelope then threw on `.data.features`,
  // taking the whole page down over a capability flag. Reading a capability is
  // never worth a crash.
  const purgeVisible =
    identity?.principalType === 'ENVIRONMENT_ROOT'
    && capabilityData?.data?.features?.optional?.collectionPurge === true;
  const deleteMutation = useMutation({
    mutationKey: ['delete-collection'],
    mutationFn: (collectionKey: string) => collectionsApi.deleteByKey(collectionKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      showToast(t('collections.deleteSuccess'), 'success');
    },
    onError: () => {
      showToast(t('collections.deleteError'), 'error');
    },
  });

  return (
    <div>
      <PageHeader
        title={t('collections.title')}
        actions={
          <button
            ref={createTriggerRef}
            onClick={() => setShowCreateModal(true)}
            className={styles.createBtn}
          >
            + {t('collections.create')}
          </button>
        }
      />
      {capabilityError && (
        <QueryErrorBanner
          onRetry={() => void refetchCapability()}
          retryLabel={t('common.retry')}
        >
          {t('collections.capabilityLoadFailed')}
        </QueryErrorBanner>
      )}
      {isPending ? (
        <div className={styles.grid}>
          {[1, 2, 3].map(i => (
            <Card key={i}>
              <Skeleton width="60%" height="1.25rem" />
              <Skeleton width="40%" height="0.875rem" />
              <Skeleton width="30%" height="0.75rem" />
              <Skeleton width="80px" height="2rem" />
            </Card>
          ))}
        </div>
      ) : isError ? (
        // 过去的空态判定是 `data?.data?.collections?.length === 0`，失败时
        // data 是 undefined，比较不成立，于是连"暂无集合"都不显示——
        // 整页只剩一个"新建集合"按钮，看不出是没数据还是没连上。
        <QueryErrorBanner onRetry={() => void refetch()} retryLabel={t('common.retry')}>
          {t('collections.loadFailed')}
        </QueryErrorBanner>
      ) : (
        <div className={styles.grid}>
          {data?.data?.collections?.map(col => (
            <Card key={col.collectionKey}>
              <div className={styles.name}>{col.name}</div>
              <div className={styles.meta} title={col.collectionKey}>{col.collectionKey}</div>
              <div className={styles.meta}>
                {col.embeddingModel} · {col.dimensions}D
              </div>
              <div className={styles.meta}>
                {col.documentCount} {t('collections.documentCount')}
              </div>
              <div className={styles.actions}>
                <button
                  onClick={() => navigate(`/documents?collectionKey=${encodeURIComponent(col.collectionKey)}`)}
                  className={styles.viewBtn}
                >
                  {t('collections.viewDocuments')}
                </button>
                <button
                  onClick={() => navigate(`/embeddings?collectionKey=${encodeURIComponent(col.collectionKey)}`)}
                  className={styles.viewBtn}
                >
                  {t('embeddings.openOperations')}
                </button>
                <button
                  onClick={() => deleteMutation.mutate(col.collectionKey)}
                  className={styles.deleteBtn}
                  disabled={deleteMutation.isPending}
                >
                  {t('collections.delete')}
                </button>
                {purgeVisible && (
                  <button
                    type="button"
                    onClick={() => setPurgeTarget(col)}
                    className={styles.purgeBtn}
                  >
                    {t('collections.purge.action')}
                  </button>
                )}
              </div>
            </Card>
          ))}
          {data?.data?.collections?.length === 0 && (
            <EmptyState>{t('collections.noCollections')}</EmptyState>
          )}
        </div>
      )}
      <CreateCollectionModal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        returnFocusRef={createTriggerRef}
      />
      {purgeTarget && (
        <CollectionPurgeDialog
          collection={purgeTarget}
          onClose={() => setPurgeTarget(null)}
        />
      )}
    </div>
  );
}

function CollectionPurgeDialog({
  collection,
  onClose,
}: {
  collection: Collection;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const [preview, setPreview] = useState<CollectionPurgePreview | null>(null);
  const [result, setResult] = useState<CollectionPurgeResult | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);

  const previewMutation = useMutation({
    mutationKey: ['preview-collection-purge', collection.collectionKey],
    mutationFn: () => collectionsApi.previewPurge(collection.collectionKey),
    onSuccess: (response) => {
      setPreview(response.data);
      setPreviewError(null);
      setApplyError(null);
      setConfirmation('');
    },
    onError: (error) => {
      setPreviewError(errorMessage(error));
    },
  });

  const applyMutation = useMutation({
    mutationKey: ['apply-collection-purge', collection.collectionKey],
    mutationFn: () => {
      if (!preview) {
        throw new Error(t('collections.purge.previewRequired'));
      }
      return collectionsApi.applyPurge({
        collectionKey: preview.collectionKey,
        previewId: preview.previewId,
        confirmationToken: preview.confirmationToken,
        fingerprint: preview.fingerprint,
        expectedCollectionVersion: preview.collectionVersion,
        expectedChatCommitFenceVersion: preview.chatCommitFenceVersion,
      });
    },
    onSuccess: (response) => {
      setResult(response.data);
      setApplyError(null);
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      showToast(t('collections.purge.success'), 'success');
    },
    onError: (error) => {
      const message = errorMessage(error);
      setApplyError(message);
      showToast(`${t('collections.purge.applyError')}: ${message}`, 'error');
    },
  });

  useEffect(() => {
    previewMutation.mutate();
    // A new dialog instance is mounted for each selected Collection.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collection.collectionKey]);

  const canApply =
    preview !== null
    && confirmation === collection.collectionKey
    && !applyMutation.isPending;

  const metrics = preview ? [
    [t('collections.purge.metrics.documents'), preview.documentCount],
    [t('collections.purge.metrics.localDocuments'), preview.localDocumentCount],
    [t('collections.purge.metrics.externalDocuments'), preview.externalDocumentCount],
    [t('collections.purge.metrics.embeddings'), preview.embeddingCount],
    [t('collections.purge.metrics.embeddingJobs'), preview.embeddingJobCount],
    [t('collections.purge.metrics.versions'), preview.versionCount],
    [t('collections.purge.metrics.keywordChunks'), preview.keywordChunkCount],
    [t('collections.purge.metrics.derivedRows'), preview.derivedRowCount],
    [t('collections.purge.metrics.feedback'), preview.feedbackCount],
    [t('collections.purge.metrics.affectedSessions'), preview.affectedChatSessionCount],
    [t('collections.purge.metrics.chatRows'),
      preview.chatHistoryCount
      + preview.chatMemoryCount
      + preview.chatSummaryCount
      + preview.chatTurnOperationCount],
    [t('collections.purge.metrics.auditRows'),
      preview.documentAuditCount + preview.collectionAuditCount],
  ] as const : [];

  const closeDialog = () => {
    if (!applyMutation.isPending) {
      onClose();
    }
  };

  return (
    <Dialog
      open
      title={t('collections.purge.title')}
      description={t('collections.purge.description', {
        collectionKey: collection.collectionKey,
      })}
      onClose={closeDialog}
      closeDisabled={applyMutation.isPending}
      size="large"
      actions={(
        <>
          <button
            type="button"
            className={styles.secondaryBtn}
            onClick={closeDialog}
            disabled={applyMutation.isPending}
          >
            {result ? t('common.close') : t('common.cancel')}
          </button>
          {preview && !result && (
            <button
              type="button"
              className={styles.purgeConfirmBtn}
              onClick={() => applyMutation.mutate()}
              disabled={!canApply}
            >
              {applyMutation.isPending
                ? t('collections.purge.applying')
                : t('collections.purge.confirmAction')}
            </button>
          )}
        </>
      )}
    >
        <div className={styles.purgeBody}>
          {previewMutation.isPending && (
            <p role="status">{t('collections.purge.previewLoading')}</p>
          )}

          {previewError && (
            <div className={styles.errorPanel} role="alert">
              <strong>{t('collections.purge.previewError')}</strong>
              <span>{previewError}</span>
              <button
                type="button"
                className={styles.secondaryBtn}
                onClick={() => previewMutation.mutate()}
                disabled={previewMutation.isPending}
              >
                {t('collections.purge.retryPreview')}
              </button>
            </div>
          )}

          {result && (
            <div className={styles.successPanel} role="status">
              <strong>{t('collections.purge.resultTitle')}</strong>
              <span>
                {t('collections.purge.resultSummary', {
                  collectionKey: result.collectionKey,
                  count: result.purgedDocumentCount,
                })}
              </span>
              <span>
                {t('collections.purge.resultVersion', {
                  version: result.collectionVersion,
                })}
              </span>
            </div>
          )}

          {preview && !result && (
            <>
              <div className={styles.warningPanel} role="note">
                <strong>{t('collections.purge.warningTitle')}</strong>
                <span>{t('collections.purge.warning')}</span>
              </div>

              <dl className={styles.purgeMetrics}>
                {metrics.map(([label, value]) => (
                  <div key={label} className={styles.purgeMetric}>
                    <dt>{label}</dt>
                    <dd>{value}</dd>
                  </div>
                ))}
              </dl>

              <div className={styles.expiry}>
                {t('collections.purge.expiresAt', {
                  time: new Date(preview.previewExpiresAt).toLocaleString(),
                })}
              </div>

              <label className={styles.confirmLabel} htmlFor="collection-purge-confirmation">
                {t('collections.purge.confirmLabel', {
                  collectionKey: collection.collectionKey,
                })}
              </label>
              <input
                id="collection-purge-confirmation"
                className={styles.confirmInput}
                value={confirmation}
                onChange={event => setConfirmation(event.target.value)}
                autoComplete="off"
                spellCheck={false}
                autoFocus
              />

              {applyError && (
                <div className={styles.errorPanel} role="alert">
                  <strong>{t('collections.purge.applyError')}</strong>
                  <span>{applyError}</span>
                </div>
              )}
            </>
          )}
        </div>
    </Dialog>
  );
}
