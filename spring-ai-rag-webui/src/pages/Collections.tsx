import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { collectionsApi } from '../api/collections';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { CreateCollectionModal } from '../components/CreateCollectionModal';
import styles from './Collections.module.css';

export function Collections() {
  const { t } = useTranslation();
  const [page] = useState(0);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const { data, isPending } = useQuery({
    queryKey: ['collections', page],
    queryFn: () => collectionsApi.list({ page, size: 20 }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => collectionsApi.delete(id),
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
      <div className={styles.header}>
        <h1 className="page-title">{t('collections.title')}</h1>
        <button
          onClick={() => setShowCreateModal(true)}
          className={styles.createBtn}
        >
          + {t('collections.create')}
        </button>
      </div>
      {isPending ? (
        <div className={styles.grid}>
          {[1, 2, 3].map(i => (
            <div key={i} className={styles.card}>
              <Skeleton width="60%" height="1.25rem" />
              <Skeleton width="40%" height="0.875rem" />
              <Skeleton width="30%" height="0.75rem" />
              <Skeleton width="80px" height="2rem" />
            </div>
          ))}
        </div>
      ) : (
        <div className={styles.grid}>
          {data?.data?.collections?.map(col => (
            <div key={col.id} className={styles.card}>
              <div className={styles.name}>{col.name}</div>
              <div className={styles.meta}>
                {col.embeddingModel} · {col.dimensions}D
              </div>
              <div className={styles.meta}>
                {col.documentCount} {t('collections.documentCount')}
              </div>
              <div className={styles.actions}>
                <button
                  onClick={() => navigate(`/documents?collectionId=${col.id}`)}
                  className={styles.viewBtn}
                >
                  View Documents
                </button>
                <button
                  onClick={() => deleteMutation.mutate(col.id)}
                  className={styles.deleteBtn}
                  disabled={deleteMutation.isPending}
                >
                  {t('collections.delete')}
                </button>
              </div>
            </div>
          ))}
          {data?.data?.collections?.length === 0 && (
            <div className={styles.empty}>{t('collections.noCollections')}</div>
          )}
        </div>
      )}
      <CreateCollectionModal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
      />
    </div>
  );
}
