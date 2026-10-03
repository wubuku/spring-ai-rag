import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { documentsApi } from '../../api/documents';
import { useToast } from '../Toast';
import { ConfirmDialog } from '../Dialog';
import { QueryErrorBanner } from '../ui';
import styles from './ReembedAllButton.module.css';
import { ChevronUp, ChevronDown, TriangleAlert } from 'lucide-react';

export function ReembedAllButton() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const [isExpanded, setIsExpanded] = useState(false);
  const [confirmForce, setConfirmForce] = useState(false);

  const {
    data: status,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['embeddingStatus'],
    queryFn: () => documentsApi.getEmbeddingStatus(),
    refetchInterval: 30000, // Refresh every 30s
  });

  const reembedMutation = useMutation({
    mutationFn: (force: boolean) => documentsApi.reembedMissing(force),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['embeddingStatus'] });
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(
        result.data.failed > 0
          ? t('documents.reembedSuccessPartial', {
              success: result.data.success,
              failed: result.data.failed,
            })
          : t('documents.reembedSuccess', { success: result.data.success }),
        result.data.failed > 0 ? 'warning' : 'success'
      );
      setIsExpanded(false);
    },
    onError: (err: Error) => {
      showToast(t('documents.reembedError', { message: err.message }), 'error');
    },
  });

  if (isError) {
    // `isLoading || !status` 会把失败也吞成骨架屏：react-query 重试耗尽后
    // isLoading 变 false、status 仍是 undefined，于是这一块永远停在灰色骨架，
    // 既不显示"还有多少文档没嵌入"，也不告诉用户为什么。
    return (
      <QueryErrorBanner
        onRetry={() => void refetch()}
        retryLabel={t('common.retry')}
        detail={error instanceof Error ? error.message : undefined}
      >
        {t('documents.missingEmbeddingsLoadFailed')}
      </QueryErrorBanner>
    );
  }

  if (isLoading || !status) {
    return <div className={styles.skeleton} />;
  }

  if (!status.data.hasMissing) {
    return null; // All documents have embeddings, hide the button
  }

  return (
    <div className={styles.container}>
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        className={styles.alertButton}
        title={t('documents.reembedAlert')}
      >
        <TriangleAlert className={styles.icon} size={18} aria-hidden="true" />
        <span className={styles.text}>
          {status.data.withoutEmbeddings} {t('documents.missingEmbeddings')}
        </span>
        <span className={styles.arrow}>
            {isExpanded ? (
              <ChevronUp size={16} aria-hidden="true" />
            ) : (
              <ChevronDown size={16} aria-hidden="true" />
            )}
          </span>
      </button>

      {isExpanded && (
        <div className={styles.panel}>
          <p className={styles.message}>
            {t('documents.reembedDescription')}
          </p>
          <div className={styles.actions}>
            <button
              onClick={() => reembedMutation.mutate(false)}
              disabled={reembedMutation.isPending}
              className={styles.reembedBtn}
            >
              {reembedMutation.isPending ? t('common.loading') : t('documents.reembed')}
            </button>
            <button
              onClick={() => setConfirmForce(true)}
              disabled={reembedMutation.isPending}
              className={styles.forceBtn}
            >
              {t('documents.reembedForce')}
            </button>
          </div>
        </div>
      )}
      <ConfirmDialog
        open={confirmForce}
        title={t('documents.reembedForce')}
        description={t('documents.reembedForceConfirm')}
        confirmLabel={t('documents.reembedForce')}
        cancelLabel={t('common.cancel')}
        pending={reembedMutation.isPending}
        danger
        onClose={() => setConfirmForce(false)}
        onConfirm={() => {
          setConfirmForce(false);
          reembedMutation.mutate(true);
        }}
      />
    </div>
  );
}
