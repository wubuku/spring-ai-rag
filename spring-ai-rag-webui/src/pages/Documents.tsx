import { useCallback, useState } from 'react';
import { ReembedAllButton } from '../components/ReembedAllButton';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { documentsApi } from '../api/documents';
import type { Document } from '../api/documents';
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
  const [versionsDoc, setVersionsDoc] = useState<Document | null>(null);
  const [editDoc, setEditDoc] = useState<Document | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editSource, setEditSource] = useState('');
  const [editCollectionKey, setEditCollectionKey] = useState('');
  const [editEmbeddingPolicy, setEditEmbeddingPolicy] =
    useState<'SYNC' | 'ASYNC' | 'SKIP'>('ASYNC');
  const [relocateDoc, setRelocateDoc] = useState<Document | null>(null);
  const [relocateTarget, setRelocateTarget] = useState('');
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

  const handleMutationError = (error: unknown, fallbackKey: string) => {
    const status = (error as { response?: { status?: number } })?.response?.status;
    if (status === 409) {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.revisionConflict'), 'error');
      return;
    }
    showToast(t(fallbackKey), 'error');
  };

  const updateMutation = useMutation({
    mutationFn: () => {
      if (!editDoc?.documentRevision) {
        throw new Error('Missing document revision');
      }
      return documentsApi.update(editDoc.id, {
        expectedDocumentRevision: editDoc.documentRevision,
        title: editTitle,
        content: editContent,
        source: editSource.trim() || null,
        collectionKey: editCollectionKey || null,
        embeddingPolicy: editEmbeddingPolicy,
      });
    },
    onSuccess: () => {
      setEditDoc(null);
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.updated'), 'success');
    },
    onError: error => {
      handleMutationError(error, 'documents.updateError');
    },
  });

  const disableMutation = useMutation({
    mutationFn: (doc: Document) => documentsApi.disable(
      doc.id,
      requireDocumentRevision(doc),
    ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.disabled'), 'success');
    },
    onError: error => {
      handleMutationError(error, 'documents.disableError');
    },
  });

  const restoreMutation = useMutation({
    mutationFn: (doc: Document) => documentsApi.restore(
      doc.id,
      requireDocumentRevision(doc),
      'ASYNC',
    ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.restored'), 'success');
    },
    onError: error => {
      handleMutationError(error, 'documents.restoreError');
    },
  });

  const restoreVersionMutation = useMutation({
    mutationFn: ({ document, versionNumber }: {
      document: Document;
      versionNumber: number;
    }) => documentsApi.restoreVersion(
      document.id,
      versionNumber,
      requireDocumentRevision(document),
      'ASYNC',
      'KEEP_CURRENT',
    ),
    onSuccess: () => {
      setVersionsDoc(null);
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('versions.restored'), 'success');
    },
    onError: error => {
      queryClient.invalidateQueries({ queryKey: ['document-versions'] });
      handleMutationError(error, 'versions.restoreError');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (doc: Document) => documentsApi.delete(
      doc.id,
      requireDocumentRevision(doc),
    ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.permanentlyDeleted'), 'success');
    },
    onError: error => {
      handleMutationError(error, 'documents.deleteError');
    },
  });

  const embedMutation = useMutation({
    mutationFn: ({ id, force }: { id: number; force: boolean }) =>
      documentsApi.embed(id, force),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.embeddingRetried'), 'success');
    },
    onError: () => {
      showToast(t('documents.embeddingRetryError'), 'error');
    },
  });

  const relocateMutation = useMutation({
    mutationFn: () => {
      if (!relocateDoc?.collectionKey || !relocateDoc.externalId
          || !relocateDoc.sourceRevision || !relocateTarget) {
        throw new Error('Incomplete external relocation request');
      }
      return documentsApi.relocate({
        sourceCollectionKey: relocateDoc.collectionKey,
        targetCollectionKey: relocateTarget,
        sourceNamespace: relocateDoc.sourceNamespace || 'default',
        externalId: relocateDoc.externalId,
        expectedSourceRevision: relocateDoc.sourceRevision,
      }, crypto.randomUUID());
    },
    onSuccess: response => {
      setRelocateDoc(null);
      setRelocateTarget('');
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      queryClient.invalidateQueries({ queryKey: ['embedding-readiness'] });
      queryClient.invalidateQueries({ queryKey: ['derivation-readiness'] });
      showToast(t('documents.relocated', {
        target: response.data.targetCollectionKey,
      }), 'success');
    },
    onError: error => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      const code = (error as {
        response?: { data?: { error?: string } };
      })?.response?.data?.error;
      showToast(t(`documents.relocationErrors.${code || 'DEFAULT'}`), 'error');
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

  const handleEdit = async (doc: Document) => {
    try {
      const response = await documentsApi.get(doc.id);
      const detail = response.data;
      setEditDoc(detail);
      setEditTitle(detail.title);
      setEditContent(detail.content || '');
      setEditSource(detail.source || '');
      setEditCollectionKey(detail.collectionKey || '');
      setEditEmbeddingPolicy('ASYNC');
    } catch (err) {
      handleMutationError(err, 'documents.loadDetailError');
    }
  };

  const handleRelocate = async (doc: Document) => {
    try {
      const detail = (await documentsApi.get(doc.id)).data;
      setRelocateDoc(detail);
      setRelocateTarget('');
    } catch (err) {
      handleMutationError(err, 'documents.loadDetailError');
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
  const mutationPending = updateMutation.isPending
    || disableMutation.isPending
    || restoreMutation.isPending
    || deleteMutation.isPending;

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
                  <th>{t('documents.sourceNamespace')}</th>
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
                    <td className={styles.revision}>
                      {doc.sourceNamespace ?? (doc.externalId ? 'default' : '—')}
                    </td>
                    <td className={styles.externalId} title={doc.externalId ?? undefined}>
                      {doc.externalId ?? '—'}
                    </td>
                    <td className={styles.revision} title={doc.sourceRevision ?? undefined}>
                      {doc.sourceRevision ?? '—'}
                    </td>
                    <td>{doc.documentType ?? '—'}</td>
                    <td>
                      <span
                        className={`${styles.lifecycle} ${lifecycleClass(
                          doc.lifecycle?.searchability,
                          doc.embeddingFresh,
                          doc.enabled,
                        )}`}
                        title={lifecycleTitle(doc, t)}
                      >
                        {lifecycleLabel(doc, t)}
                      </span>
                      {(doc.lifecycle?.lastError || doc.processingError) && (
                        <div className={styles.processingError}>
                          {doc.lifecycle?.lastError || doc.processingError}
                        </div>
                      )}
                    </td>
                    <td>{new Date(doc.createdAt).toLocaleDateString()}</td>
                    <td className={styles.hash}>{doc.contentHash?.slice(0, 8)}...</td>
                    <td className={styles.actionCell}>
                      <DocumentActionsMenu
                        ragDocument={doc}
                        embeddingPending={embedMutation.isPending}
                        mutationPending={mutationPending}
                        onPreview={() => handlePreview(doc)}
                        onVersions={() => setVersionsDoc(doc)}
                        onEdit={() => handleEdit(doc)}
                        onRetryEmbedding={() => embedMutation.mutate({
                          id: doc.id,
                          force: doc.lifecycle?.retryable === true,
                        })}
                        onDisable={() => {
                          if (confirm(t('documents.disableConfirm'))) {
                            disableMutation.mutate(doc);
                          }
                        }}
                        onRestore={() => restoreMutation.mutate(doc)}
                        onPermanentDelete={() => {
                          if (confirm(t('documents.permanentDeleteConfirm'))) {
                            deleteMutation.mutate(doc);
                          }
                        }}
                        onRelocate={() => handleRelocate(doc)}
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

      {editDoc && (
        <div className={styles.modalOverlay} onClick={() => setEditDoc(null)}>
          <form
            className={styles.editModal}
            aria-label={t('documents.editDocument')}
            onClick={event => event.stopPropagation()}
            onSubmit={event => {
              event.preventDefault();
              updateMutation.mutate();
            }}
          >
            <div className={styles.modalHeader}>
              <h2 className={styles.modalTitle}>{t('documents.editDocument')}</h2>
              <button
                type="button"
                className={styles.modalClose}
                aria-label={t('common.close')}
                onClick={() => setEditDoc(null)}
              >
                ×
              </button>
            </div>
            <div className={styles.editFields}>
              <label>
                <span>{t('documents.title')}</span>
                <input
                  value={editTitle}
                  maxLength={255}
                  required
                  onChange={event => setEditTitle(event.target.value)}
                />
              </label>
              <label>
                <span>{t('documents.source')}</span>
                <input
                  value={editSource}
                  maxLength={255}
                  onChange={event => setEditSource(event.target.value)}
                />
              </label>
              <label>
                <span>{t('documents.collection')}</span>
                <select
                  value={editCollectionKey}
                  onChange={event => setEditCollectionKey(event.target.value)}
                >
                  <option value="">{t('documents.unassigned')}</option>
                  {collections.map((collection: {
                    id: number;
                    collectionKey: string;
                    name: string;
                  }) => (
                    <option
                      key={collection.collectionKey}
                      value={collection.collectionKey}
                    >
                      {collection.name} ({collection.collectionKey})
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>{t('documents.embeddingPolicy')}</span>
                <select
                  value={editEmbeddingPolicy}
                  onChange={event => setEditEmbeddingPolicy(
                    event.target.value as 'SYNC' | 'ASYNC' | 'SKIP',
                  )}
                >
                  <option value="ASYNC">ASYNC</option>
                  <option value="SYNC">SYNC</option>
                  <option value="SKIP">SKIP</option>
                </select>
              </label>
              <label className={styles.contentField}>
                <span>{t('documents.content')}</span>
                <textarea
                  value={editContent}
                  required
                  onChange={event => setEditContent(event.target.value)}
                />
              </label>
            </div>
            <div className={styles.modalActions}>
              <button type="button" onClick={() => setEditDoc(null)}>
                {t('common.cancel')}
              </button>
              <button type="submit" disabled={updateMutation.isPending}>
                {updateMutation.isPending
                  ? t('common.loading')
                  : t('common.save')}
              </button>
            </div>
          </form>
        </div>
      )}

      {relocateDoc && (
        <div className={styles.modalOverlay} onClick={() => setRelocateDoc(null)}>
          <form
            className={styles.editModal}
            aria-label={t('documents.relocateTitle')}
            onClick={event => event.stopPropagation()}
            onSubmit={event => {
              event.preventDefault();
              relocateMutation.mutate();
            }}
          >
            <div className={styles.modalHeader}>
              <h2 className={styles.modalTitle}>{t('documents.relocateTitle')}</h2>
              <button
                type="button"
                className={styles.modalClose}
                aria-label={t('common.close')}
                onClick={() => setRelocateDoc(null)}
              >
                ×
              </button>
            </div>
            <div className={styles.editFields}>
              <label>
                <span>{t('documents.collection')}</span>
                <input value={relocateDoc.collectionKey || ''} readOnly />
              </label>
              <label>
                <span>{t('documents.targetCollection')}</span>
                <select
                  value={relocateTarget}
                  required
                  onChange={event => setRelocateTarget(event.target.value)}
                >
                  <option value="">{t('documents.selectTargetCollection')}</option>
                  {collections
                    .filter((collection: { collectionKey: string }) =>
                      collection.collectionKey !== relocateDoc.collectionKey)
                    .map((collection: { collectionKey: string; name: string }) => (
                      <option key={collection.collectionKey} value={collection.collectionKey}>
                        {collection.name} ({collection.collectionKey})
                      </option>
                    ))}
                </select>
              </label>
              <label>
                <span>{t('documents.sourceNamespace')}</span>
                <input value={relocateDoc.sourceNamespace || 'default'} readOnly />
              </label>
              <label>
                <span>{t('documents.externalId')}</span>
                <input value={relocateDoc.externalId || ''} readOnly />
              </label>
              <label>
                <span>{t('documents.sourceRevision')}</span>
                <input value={relocateDoc.sourceRevision || ''} readOnly />
              </label>
            </div>
            <div className={styles.modalActions}>
              <button type="button" onClick={() => setRelocateDoc(null)}>
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                disabled={!relocateTarget || relocateMutation.isPending}
              >
                {relocateMutation.isPending
                  ? t('common.loading') : t('documents.relocateConfirm')}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Version History Modal */}
      {versionsDoc && (
        <VersionHistoryModal
          documentId={versionsDoc.id}
          documentTitle={versionsDoc.title}
          documentRevision={versionsDoc.documentRevision}
          externallyManaged={Boolean(versionsDoc.externalId)}
          restorePending={restoreVersionMutation.isPending}
          onRestoreVersion={versionNumber => {
            const confirmed = window.confirm(
              t('versions.restoreConfirm', {
                version: versionNumber,
                defaultValue: `Restore version ${versionNumber} as a new revision?`,
              }),
            );
            if (confirmed) {
              restoreVersionMutation.mutate({
                document: versionsDoc,
                versionNumber,
              });
            }
          }}
          onClose={() => setVersionsDoc(null)}
        />
      )}
    </div>
  );
}

function requireDocumentRevision(document: Document): number {
  if (!document.documentRevision) {
    throw new Error('Missing document revision');
  }
  return document.documentRevision;
}

function lifecycleClass(
  searchability: string | undefined,
  embeddingFresh: boolean | undefined,
  enabled: boolean | undefined,
): string {
  const value = enabled === false
    ? 'DISABLED'
    : searchability || (embeddingFresh ? 'READY' : 'NOT_REQUESTED');
  return styles[`lifecycle${value}`] || styles.lifecycleNOT_REQUESTED;
}

function lifecycleLabel(
  document: Document,
  translate: (key: string) => string,
): string {
  const value = document.enabled === false
    ? 'DISABLED'
    : document.lifecycle?.searchability
      || (document.embeddingFresh ? 'READY' : 'NOT_REQUESTED');
  return translate(`documents.lifecycle.${value}`);
}

function lifecycleTitle(
  document: Document,
  translate: (key: string) => string,
): string | undefined {
  const value = document.enabled === false
    ? 'DISABLED'
    : document.lifecycle?.searchability
      || (document.embeddingFresh ? 'READY' : 'NOT_REQUESTED');
  if (value === 'KEYWORD_ONLY') {
    return translate('documents.keywordOnlyHint');
  }
  return document.lifecycle?.lastError ?? document.processingError ?? undefined;
}
