import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { documentsApi } from '../../api/documents';
import { useToast } from '../Toast';
import styles from './ReembedAllButton.module.css';

export function ReembedAllButton() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const [isExpanded, setIsExpanded] = useState(false);

  const { data: status, isLoading } = useQuery({
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
        `Re-embedded: ${result.data.success} success${result.data.failed > 0 ? `, ${result.data.failed} failed` : ''}`,
        result.data.failed > 0 ? 'warning' : 'success'
      );
      setIsExpanded(false);
    },
    onError: (err: Error) => {
      showToast(`Re-embed failed: ${err.message}`, 'error');
    },
  });

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
        title={t('documents.reembedAlert') || 'Documents missing embeddings'}
      >
        <span className={styles.icon}>⚠️</span>
        <span className={styles.text}>
          {status.data.withoutEmbeddings} {t('documents.missingEmbeddings') || 'documents need re-embedding'}
        </span>
        <span className={styles.arrow}>{isExpanded ? '▲' : '▼'}</span>
      </button>

      {isExpanded && (
        <div className={styles.panel}>
          <p className={styles.message}>
            {t('documents.reembedDescription') ||
              `Found ${status.data.withoutEmbeddings} documents without embeddings. This may happen after data migration.`}
          </p>
          <div className={styles.actions}>
            <button
              onClick={() => reembedMutation.mutate(false)}
              disabled={reembedMutation.isPending}
              className={styles.reembedBtn}
            >
              {reembedMutation.isPending ? t('common.loading') : t('documents.reembed') || 'Re-embed All'}
            </button>
            <button
              onClick={() => {
                if (confirm(t('documents.reembedForceConfirm') || 'Force re-embed will regenerate ALL embeddings. Continue?')) {
                  reembedMutation.mutate(true);
                }
              }}
              disabled={reembedMutation.isPending}
              className={styles.forceBtn}
            >
              {t('documents.reembedForce') || 'Force Re-embed'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
