import { useCallback, useState } from 'react';
import { ReembedAllButton } from '../components/ReembedAllButton';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { documentsApi } from '../api/documents';
import { collectionsApi } from '../api/collections';
import { filesApi } from '../api/files';
import { useFileUpload } from '../hooks/useFileUpload';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { DocumentActionsMenu } from '../components/DocumentActionsMenu/DocumentActionsMenu';
import { VersionHistoryModal } from '../components/VersionHistoryModal/VersionHistoryModal';
import styles from './Documents.module.css';

export function Documents() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const rawPage = Number(searchParams.get('page') ?? 0);
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0;
  const keyword = searchParams.get('keyword') ?? '';
  const selectedCollection = searchParams.get('collectionKey') || undefined;
  const [previewDoc, setPreviewDoc] = useState<{ id: number; title: string; content: string } | null>(null);
  const [versionsDoc, setVersionsDoc] = useState<{ id: number; title: string } | null>(null);
  const PAGE_SIZE = 20;
  const queryClient = useQueryClient();
  const { showToast } = useToast();

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
        collectionKey: selectedCollection,
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

  const embedMutation = useMutation({
    mutationFn: (id: number) => documentsApi.embed(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.embeddingRetried'), 'success');
    },
    onError: () => {
      showToast(t('documents.embeddingRetryError'), 'error');
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
    const next = new URLSearchParams(searchParams);
    const value = e.target.value;
    if (value) next.set('keyword', value);
    else next.delete('keyword');
    next.delete('page');
    setSearchParams(next, { replace: true });
  };

  const handleCollectionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const next = new URLSearchParams(searchParams);
    const value = e.target.value;
    if (value) next.set('collectionKey', value);
    else next.delete('collectionKey');
    next.delete('page');
    setSearchParams(next);
  };

  const handlePageChange = (nextPage: number) => {
    const next = new URLSearchParams(searchParams);
    if (nextPage > 0) next.set('page', String(nextPage));
    else next.delete('page');
    setSearchParams(next);
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

  const handleViewDirectory = useCallback((path: string) => {
    const params = new URLSearchParams({ path });
    navigate(`/files?${params.toString()}`);
  }, [navigate]);

  const handleViewIndexedFile = useCallback((
    directoryPath: string,
    filePath: string,
  ) => {
    const params = new URLSearchParams({
      path: directoryPath,
      file: filePath,
    });
    navigate(`/files?${params.toString()}`);
  }, [navigate]);

  const handleOpenOriginalFile = useCallback(async (path: string) => {
    try {
      const blob = await filesApi.getRawFile(path);
      const objectUrl = URL.createObjectURL(blob);
      window.open(objectUrl, '_blank', 'noopener,noreferrer');
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      showToast(t('documents.openOriginalPdfError', { error: message }), 'error');
    }
  }, [showToast, t]);

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
      <p>
        <Link to={selectedCollection ? `/embeddings?collectionKey=${encodeURIComponent(selectedCollection)}` : '/embeddings'}>
          {t('embeddings.openOperations')}
        </Link>
      </p>

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
              const next = new URLSearchParams(searchParams);
              next.delete('keyword');
              next.delete('page');
              setSearchParams(next);
            }}
            className={styles.clearBtn}
          >
            ✕
          </button>
        )}
        <select
          data-testid="documents-collection-filter"
          value={selectedCollection ?? ''}
          onChange={handleCollectionChange}
          className={styles.filterSelect}
        >
          <option value="">All Collections</option>
          {collections.map((c: { id: number; collectionKey: string; name: string }) => (
            <option key={c.collectionKey} value={c.collectionKey}>
              {c.name} ({c.collectionKey})
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
                  <th>{t('documents.externalId')}</th>
                  <th>{t('documents.sourceRevision')}</th>
                  <th>{t('documents.documentType')}</th>
                  <th>{t('documents.embeddingStatus')}</th>
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
                    <td className={styles.externalId} title={doc.externalId ?? undefined}>
                      {doc.externalId ?? '—'}
                    </td>
                    <td className={styles.revision} title={doc.sourceRevision ?? undefined}>
                      {doc.sourceRevision ?? '—'}
                    </td>
                    <td>{doc.documentType ?? '—'}</td>
                    <td>
                      <span
                        className={doc.embeddingFresh ? styles.fresh : styles.stale}
                        title={doc.processingError ?? undefined}
                      >
                        {doc.embeddingFresh
                          ? t('documents.embeddingFresh')
                          : t('documents.embeddingStale')}
                      </span>
                      {doc.processingError && (
                        <div className={styles.processingError}>{doc.processingError}</div>
                      )}
                    </td>
                    <td>{new Date(doc.createdAt).toLocaleDateString()}</td>
                    <td className={styles.hash}>{doc.contentHash?.slice(0, 8)}...</td>
                    <td className={styles.actionCell}>
                      <DocumentActionsMenu
                        ragDocument={doc}
                        embeddingPending={embedMutation.isPending}
                        deletePending={deleteMutation.isPending}
                        onPreview={() => handlePreview(doc)}
                        onVersions={() => setVersionsDoc({ id: doc.id, title: doc.title })}
                        onRetryEmbedding={() => embedMutation.mutate(doc.id)}
                        onDelete={() => deleteMutation.mutate(doc.id)}
                        onViewDirectory={handleViewDirectory}
                        onViewIndexedFile={handleViewIndexedFile}
                        onOpenOriginalFile={handleOpenOriginalFile}
                      />
                    </td>
                  </tr>
                ))}
                {data?.data?.documents?.length === 0 && (
                  <tr>
                    <td colSpan={11} className={styles.empty}>
                      {t('documents.noDocuments')}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className={styles.pagination}>
            <button
              onClick={() => handlePageChange(page - 1)}
              disabled={page === 0}
              className={styles.pageBtn}
            >
              {t('common.previous')}
            </button>
            <span className={styles.pageInfo}>
              Page {page + 1} — {t('documents.totalDocuments')}: {data?.data?.total ?? 0}
            </span>
            <button
              onClick={() => handlePageChange(page + 1)}
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
