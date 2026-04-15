import { useState, useEffect } from 'react';
import { ReembedAllButton } from '../components/ReembedAllButton';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';
import { useFileUpload } from '../hooks/useFileUpload';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { VersionHistoryModal } from '../components/VersionHistoryModal/VersionHistoryModal';
import styles from './Documents.module.css';

export function Documents() {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [selectedCollection, setSelectedCollection] = useState<number | undefined>(undefined);
  const [previewDoc, setPreviewDoc] = useState<{ id: number; title: string; content: string } | null>(null);
  const [versionsDoc, setVersionsDoc] = useState<{ id: number; title: string } | null>(null);
  const PAGE_SIZE = 20;
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  // Read collectionId from URL on mount (e.g., when navigated from Collections page)
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const urlCollectionId = params.get('collectionId');
    if (urlCollectionId) {
      setSelectedCollection(Number(urlCollectionId));
    }
  }, []);

  const { data: collectionsData } = useQuery({
    queryKey: ['collections-all'],
    queryFn: () => collectionsApi.list({ page: 0, size: 1000 }),
  });

  const { data, isPending, error } = useQuery({
    queryKey: ['documents', page, keyword, selectedCollection],
    queryFn: () =>
      documentsApi.list({
        page,
        size: PAGE_SIZE,
        title: keyword || undefined,
        collectionId: selectedCollection,
      }),
    staleTime: 10000,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => documentsApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.deleted'), 'success');
    },
    onError: () => {
      showToast(t('documents.deleteError'), 'error');
    },
  });

  const { uploadFiles, isUploading } = useFileUpload({
    onComplete: fileName => {
      showToast(`${fileName} ${t('documents.uploaded')}`, 'success');
      queryClient.invalidateQueries({ queryKey: ['documents'] });
    },
    onError: (fileName, errorMsg) => {
      showToast(`${fileName}: ${errorMsg}`, 'error');
    },
  });

  const handleFiles = (fileList: FileList | null) => {
    if (!fileList?.length) return;
    uploadFiles(fileList);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.currentTarget.classList.add(styles.dragOver);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.currentTarget.classList.remove(styles.dragOver);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.currentTarget.classList.remove(styles.dragOver);
    handleFiles(e.dataTransfer.files);
  };

  const handleKeywordChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setKeyword(e.target.value);
    setPage(0);
  };

  const handleCollectionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const val = e.target.value;
    setSelectedCollection(val === '' ? undefined : Number(val));
    setPage(0);
  };

  const handlePreview = async (doc: { id: number; title: string; content: string }) => {
    // First show modal with existing data (content may be null from list API)
    setPreviewDoc({ id: doc.id, title: doc.title, content: doc.content || '' });
    // Then fetch full document to get content
    try {
      const response = await documentsApi.get(doc.id);
      const fullDoc = response.data;
      setPreviewDoc({ id: fullDoc.id, title: fullDoc.title, content: fullDoc.content || '' });
    } catch (err) {
      console.error('Failed to fetch document content:', err);
    }
  };

  const collections = collectionsData?.data?.collections ?? [];

  return (
    <div>
      <h1 className="page-title">{t('documents.title')}</h1>

      <div
        className={styles.uploadZone}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <input
          type="file"
          multiple
          accept=".txt,.md,.json,.xml,.html,.csv,.log"
          onChange={e => handleFiles(e.target.files)}
          className={styles.fileInput}
          disabled={isUploading}
          id="file-upload"
        />
        <label htmlFor="file-upload" className={styles.uploadLabel}>
          <span className={styles.uploadIcon}>📁</span>
          <span>
            {isUploading ? t('common.loading') : t('documents.uploadHint')}
          </span>
          <span className={styles.uploadHint}>Supports: txt, md, json, xml, html, csv, log</span>
        </label>
      </div>

      <ReembedAllButton />

      <div className={styles.searchRow}>
        <input
          type="text"
          placeholder={t('documents.searchPlaceholder') || t('common.search')}
          value={keyword}
          onChange={handleKeywordChange}
          className={styles.searchInput}
        />
        {keyword && (
          <button
            onClick={() => {
              setKeyword('');
              setPage(0);
            }}
            className={styles.clearBtn}
          >
            ✕
          </button>
        )}
        <select
          value={selectedCollection ?? ''}
          onChange={handleCollectionChange}
          className={styles.filterSelect}
        >
          <option value="">All Collections</option>
          {collections.map((c: { id: number; name: string }) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>

      {isPending ? (
        <div className={styles.tableWrapper}>
          <Skeleton width="100%" height="400px" borderRadius="8px" />
        </div>
      ) : error ? (
        <div className={styles.error}>
          {t('documents.loadError') || t('common.error')}:{' '}
          {error instanceof Error ? error.message : 'Unknown error'}
        </div>
      ) : (
        <>
          <div className={styles.tableWrapper}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>{t('documents.documentId')}</th>
                  <th>{t('documents.title') || 'Title'}</th>
                  <th>Collection</th>
                  <th>{t('documents.documentType')}</th>
                  <th>Chunks</th>
                  <th>{t('documents.createdAt')}</th>
                  <th>{t('documents.contentHash')}</th>
                  <th>{t('documents.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {data?.data?.documents?.map(doc => (
                  <tr key={doc.id}>
                    <td className={styles.id}>{doc.id}</td>
                    <td>
                      <button className={styles.previewBtn} onClick={() => handlePreview(doc)}>
                        {doc.title}
                      </button>
                    </td>
                    <td>{doc.collectionName ?? '—'}</td>
                    <td>{doc.documentType ?? '—'}</td>
                    <td>{doc.chunkCount}</td>
                    <td>{new Date(doc.createdAt).toLocaleDateString()}</td>
                    <td className={styles.hash}>{doc.contentHash?.slice(0, 8)}...</td>
                    <td>
                      <div className={styles.actions}>
                        <button
                          onClick={() => setVersionsDoc({ id: doc.id, title: doc.title })}
                          className={styles.versionsBtn}
                        >
                          {t('versions.button', 'Versions')}
                        </button>
                        <button
                          onClick={() => deleteMutation.mutate(doc.id)}
                          className={styles.deleteBtn}
                          disabled={deleteMutation.isPending}
                        >
                          {t('documents.delete')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {data?.data?.documents?.length === 0 && (
                  <tr>
                    <td colSpan={8} className={styles.empty}>
                      {t('documents.noDocuments')}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className={styles.pagination}>
            <button
              onClick={() => setPage(p => p - 1)}
              disabled={page === 0}
              className={styles.pageBtn}
            >
              {t('common.previous')}
            </button>
            <span className={styles.pageInfo}>
              Page {page + 1} — {t('documents.totalDocuments')}: {data?.data?.total ?? 0}
            </span>
            <button
              onClick={() => setPage(p => p + 1)}
              disabled={
                !data?.data?.documents?.length || (page + 1) * PAGE_SIZE >= (data?.data?.total ?? 0)
              }
              className={styles.pageBtn}
            >
              {t('common.next')}
            </button>
          </div>
        </>
      )}

      {/* Preview Modal */}
      {previewDoc && (
        <div className={styles.modalOverlay} onClick={() => setPreviewDoc(null)}>
          <div className={styles.modal} onClick={e => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <h2 className={styles.modalTitle}>{previewDoc.title}</h2>
              <button className={styles.modalClose} onClick={() => setPreviewDoc(null)}>
                ×
              </button>
            </div>
            <div className={styles.modalContent}>
              <pre className={styles.previewContent}>{previewDoc.content}</pre>
            </div>
          </div>
        </div>
      )}

      {/* Version History Modal */}
      {versionsDoc && (
        <VersionHistoryModal
          documentId={versionsDoc.id}
          documentTitle={versionsDoc.title}
          onClose={() => setVersionsDoc(null)}
        />
      )}
    </div>
  );
}
