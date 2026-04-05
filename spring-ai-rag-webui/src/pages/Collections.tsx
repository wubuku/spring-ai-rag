import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { collectionsApi } from '../api/collections';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { CreateCollectionModal } from '../components/CreateCollectionModal';
import styles from './Collections.module.css';

export function Collections() {
  const [page] = useState(0);
  const [showCreateModal, setShowCreateModal] = useState(false);
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
      showToast('Collection deleted', 'success');
    },
    onError: () => {
      showToast('Failed to delete collection', 'error');
    },
  });

  return (
    <div>
      <div className={styles.header}>
        <h1 className="page-title">Collections</h1>
        <button
          onClick={() => setShowCreateModal(true)}
          className={styles.createBtn}
        >
          + Create
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
              <div className={styles.meta}>{col.documentCount} docs</div>
              <div className={styles.actions}>
                <button
                  onClick={() => deleteMutation.mutate(col.id)}
                  className={styles.deleteBtn}
                  disabled={deleteMutation.isPending}
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
          {data?.data?.collections?.length === 0 && (
            <div className={styles.empty}>No collections found</div>
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
