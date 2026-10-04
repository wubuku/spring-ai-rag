import { useCallback, useEffect, useRef, useState } from 'react';
import { PageHeader } from '../components/ui';
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
import { ConfirmDialog, Dialog } from '../components/Dialog';
import { ImeSafeForm } from '../components/ImeSafeForm';
import { useBlobUrlOpener } from '../hooks/useBlobUrlOpener';
import { useImeComposition } from '../utils/ime';
import styles from './Documents.module.css';
import { EmptyState, IconButton, QueryErrorBanner } from '../components/ui';
import { failureMessage, usableReason } from '../utils/failureReason';
import { Upload, X } from 'lucide-react';

type DocumentConfirmation =
  | { kind: 'disable'; document: Document }
  | { kind: 'delete'; document: Document }
  | { kind: 'restore-version'; document: Document; versionNumber: number }
  | null;

export function Documents() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const rawPage = Number(searchParams.get('page') ?? 0);
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0;
  const keyword = searchParams.get('keyword') ?? '';
  const selectedCollection = searchParams.get('collectionKey') || undefined;
  const [keywordDraft, setKeywordDraft] = useState(keyword);
  const keywordIme = useImeComposition();
  const previousUrlKeywordRef = useRef(keyword);
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
  const [confirmation, setConfirmation] = useState<DocumentConfirmation>(null);
  const PAGE_SIZE = 20;
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const openBlobUrl = useBlobUrlOpener();

  const { data: collectionsData, isError: collectionsError, refetch: refetchCollections } = useQuery({
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

  // A rejected write used to be announced as `t(fallbackKey)` and nothing else,
  // so the server's reason — which `api/client.ts` had already lifted into
  // `Error.message` — was dropped here, on the path five mutations share. The
  // 409 branch is the one case where a *specific* sentence beats the raw reason,
  // so it keeps winning; everything else appends what the server said.
  const handleMutationError = (error: unknown, fallbackKey: string) => {
    const status = (error as { response?: { status?: number } })?.response?.status;
    if (status === 409) {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.revisionConflict'), 'error');
      return;
    }
    showToast(failureMessage(t, fallbackKey, error), 'error');
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
    onSettled: () => setConfirmation(null),
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
    onSettled: () => setConfirmation(null),
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
    onSettled: () => setConfirmation(null),
  });

  const embedMutation = useMutation({
    mutationFn: ({ id, force }: { id: number; force: boolean }) =>
      documentsApi.embed(id, force),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      showToast(t('documents.embeddingRetried'), 'success');
    },
    onError: (error) => {
      showToast(failureMessage(t, 'documents.embeddingRetryError', error), 'error');
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

  useEffect(() => {
    if (keyword === previousUrlKeywordRef.current) return;
    previousUrlKeywordRef.current = keyword;
    if (!keywordIme.compositionActiveRef.current) {
      setKeywordDraft(keyword);
    }
  }, [keyword, keywordIme.compositionActiveRef]);

  const commitKeyword = (value: string) => {
    const next = new URLSearchParams(searchParams);
    const normalizedValue = value.trim().slice(0, 256);
    if (normalizedValue) next.set('keyword', normalizedValue);
    else next.delete('keyword');
    next.delete('page');
    setSearchParams(next, { replace: true });
  };

  const handleKeywordChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value.slice(0, 256);
    setKeywordDraft(value);
    if (!keywordIme.isComposing(e)) {
      commitKeyword(value);
    }
  };

  const handleKeywordCompositionEnd = (
    e: React.CompositionEvent<HTMLInputElement>,
  ) => {
    keywordIme.handleCompositionEnd();
    const value = e.currentTarget.value.slice(0, 256);
    setKeywordDraft(value);
    commitKeyword(value);
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
      // 列表接口通常不带正文，所以预览是先打开弹窗、再异步补全内容的。
      // 补全失败时只写 console 的话，用户看到的就是一个**永远补不上、
      // 也不解释为什么**的弹窗——他会以为文档本来就是空的。
      showToast(failureMessage(t, 'documents.previewContentLoadError', err), 'error');
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
      openBlobUrl(objectUrl);
    } catch (err) {
      showToast(failureMessage(t, 'documents.openOriginalPdfError', err), 'error');
    }
  }, [openBlobUrl, showToast, t]);

  const collections = collectionsData?.data?.collections ?? [];
  const mutationPending = updateMutation.isPending
    || disableMutation.isPending
    || restoreMutation.isPending
    || deleteMutation.isPending;

  return (
    <div>
      <PageHeader title={t('documents.title')} description={t('documents.subtitle')} />

      {/* 集合下拉过去失败时是个空列表，用户会以为"没有可选集合"，
          于是以为筛选功能坏了，或者干脆去建一个重复的集合。 */}
      {collectionsError && (
        <QueryErrorBanner
          onRetry={() => void refetchCollections()}
          retryLabel={t('common.retry')}
        >
          {t('documents.collectionsLoadFailed')}
        </QueryErrorBanner>
      )}

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
          <Upload className={styles.uploadIcon} size={20} aria-hidden="true" />
          <span>
            {isUploading ? t('common.loading') : t('documents.uploadHint')}
          </span>
          <span className={styles.uploadHint}>
                {t('documents.supportedFormats', {
                  formats: 'txt, md, json, xml, html, csv, log',
                })}
              </span>
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
          aria-label={t('documents.searchPlaceholder')}
          placeholder={t('documents.searchPlaceholder')}
          value={keywordDraft}
          onChange={handleKeywordChange}
          onCompositionStart={keywordIme.handleCompositionStart}
          onCompositionEnd={handleKeywordCompositionEnd}
          onBlur={() => {
            if (!keywordIme.compositionActiveRef.current
                && keywordDraft.trim() !== keyword) {
              commitKeyword(keywordDraft);
            }
          }}
          className={styles.searchInput}
        />
        {keyword && (
          <IconButton
            label={t('documents.clearSearch')}
            className={styles.clearBtn}
            onClick={() => {
              setKeywordDraft('');
              commitKeyword('');
            }}
          >
            <X size={16} aria-hidden="true" />
          </IconButton>
        )}
        <select
          data-testid="documents-collection-filter"
          aria-label={t('documents.collection')}
          value={selectedCollection ?? ''}
          onChange={handleCollectionChange}
          className={styles.filterSelect}
        >
          <option value="">{t('documents.allCollections')}</option>
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
          {t('documents.loadError')}:{' '}
          {usableReason(error) || t('common.unknownError')}
        </div>
      ) : (
        <>
          <div className={styles.tableWrapper}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>{t('documents.documentId')}</th>
                  <th>{t('documents.title')}</th>
                  <th>{t('documents.collection')}</th>
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
                        onDisable={() => setConfirmation({
                          kind: 'disable',
                          document: doc,
                        })}
                        onRestore={() => restoreMutation.mutate(doc)}
                        onPermanentDelete={() => setConfirmation({
                          kind: 'delete',
                          document: doc,
                        })}
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
                    <EmptyState as="td" colSpan={11} align="center">
                      {t('documents.noDocuments')}
                    </EmptyState>
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

      <Dialog
        open={Boolean(previewDoc)}
        // 标题不能直接用文档标题：数据库里 title 允许是空串（NOT NULL 不排除
        // ''），那样会渲染出空的 <h2>，aria-labelledby 指向空串，
        // 读屏只会播报一个没有名字的"dialog"，视觉上标题栏也是空的。
        // 与 VersionHistoryModal 同一约定：前缀保证永不为空。
        title={previewDoc?.title
          ? `${t('common.preview')} — ${previewDoc.title}`
          : t('common.preview')}
        onClose={() => setPreviewDoc(null)}
        size="large"
      >
        {previewDoc && (
          <pre className={styles.previewContent}>{previewDoc.content}</pre>
        )}
      </Dialog>

      <Dialog
        open={Boolean(editDoc)}
        title={t('documents.editDocument')}
        onClose={() => setEditDoc(null)}
        closeDisabled={updateMutation.isPending}
        size="large"
        actions={editDoc ? (
          <>
            <button
              type="button"
              onClick={() => setEditDoc(null)}
              disabled={updateMutation.isPending}
            >
              {t('common.cancel')}
            </button>
            <button
              type="submit"
              form="edit-document-form"
              disabled={updateMutation.isPending}
            >
              {updateMutation.isPending ? t('common.loading') : t('common.save')}
            </button>
          </>
        ) : undefined}
      >
        {editDoc && (
          <ImeSafeForm
            id="edit-document-form"
            aria-label={t('documents.editDocument')}
            onSubmit={event => {
              event.preventDefault();
              updateMutation.mutate();
            }}
          >
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
          </ImeSafeForm>
        )}
      </Dialog>

      <Dialog
        open={Boolean(relocateDoc)}
        title={t('documents.relocateTitle')}
        onClose={() => setRelocateDoc(null)}
        closeDisabled={relocateMutation.isPending}
        size="large"
        actions={relocateDoc ? (
          <>
            <button
              type="button"
              onClick={() => setRelocateDoc(null)}
              disabled={relocateMutation.isPending}
            >
              {t('common.cancel')}
            </button>
            <button
              type="submit"
              form="relocate-document-form"
              disabled={!relocateTarget || relocateMutation.isPending}
            >
              {relocateMutation.isPending
                ? t('common.loading') : t('documents.relocateConfirm')}
            </button>
          </>
        ) : undefined}
      >
        {relocateDoc && (
          <ImeSafeForm
            id="relocate-document-form"
            aria-label={t('documents.relocateTitle')}
            onSubmit={event => {
              event.preventDefault();
              relocateMutation.mutate();
            }}
          >
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
          </ImeSafeForm>
        )}
      </Dialog>

      {/* Version History Modal */}
      {versionsDoc && (
        <VersionHistoryModal
          documentId={versionsDoc.id}
          documentTitle={versionsDoc.title}
          documentRevision={versionsDoc.documentRevision}
          externallyManaged={Boolean(versionsDoc.externalId)}
          restorePending={restoreVersionMutation.isPending}
          onRestoreVersion={versionNumber => setConfirmation({
            kind: 'restore-version',
            document: versionsDoc,
            versionNumber,
          })}
          onClose={() => setVersionsDoc(null)}
        />
      )}

      <ConfirmDialog
        open={Boolean(confirmation)}
        title={confirmation?.kind === 'disable'
          ? t('documents.disable')
          : confirmation?.kind === 'delete'
            ? t('documents.permanentDelete')
            : t('versions.restore', 'Restore')}
        description={confirmation?.kind === 'disable'
          ? t('documents.disableConfirm')
          : confirmation?.kind === 'delete'
            ? t('documents.permanentDeleteConfirm')
            : t('versions.restoreConfirm', {
                version: confirmation?.kind === 'restore-version'
                  ? confirmation.versionNumber
                  : '',
                defaultValue: 'Restore this version as a new revision?',
              })}
        confirmLabel={confirmation?.kind === 'disable'
          ? t('documents.disable')
          : confirmation?.kind === 'delete'
            ? t('documents.permanentDelete')
            : t('versions.restore', 'Restore')}
        cancelLabel={t('common.cancel')}
        danger={confirmation?.kind !== 'restore-version'}
        pending={
          disableMutation.isPending
          || deleteMutation.isPending
          || restoreVersionMutation.isPending
        }
        onClose={() => setConfirmation(null)}
        onConfirm={() => {
          if (!confirmation) return;
          if (confirmation.kind === 'disable') {
            disableMutation.mutate(confirmation.document);
          } else if (confirmation.kind === 'delete') {
            deleteMutation.mutate(confirmation.document);
          } else {
            restoreVersionMutation.mutate({
              document: confirmation.document,
              versionNumber: confirmation.versionNumber,
            });
          }
        }}
      />
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
