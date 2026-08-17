import { useState, useRef, useCallback, useMemo, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { filesApi, type TreeEntry } from '../api/files';
import { collectionsApi } from '../api/collections';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { FilePreview } from '../components/FilePreview/FilePreview';
import styles from './Files.module.css';

// ─── Helpers ────────────────────────────────────────────────────────────────

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** Build breadcrumb segments from a path string */
function pathSegments(virtualPath: string): { label: string; path: string }[] {
  if (!virtualPath) return [];
  return virtualPath.split('/').filter(Boolean).map((segment, idx, arr) => ({
    label: segment,
    path: arr.slice(0, idx + 1).join('/') + '/',
  }));
}

type ImportTimeSortDirection = 'desc' | 'asc';

interface FileDeepLink {
  directoryPath: string;
  filePath: string | null;
  sortDirection: ImportTimeSortDirection;
}

function normalizeVirtualPath(
  rawPath: string | null,
  directory: boolean,
): string | null {
  if (rawPath === null) return directory ? '' : null;
  const withoutLeadingSlash = rawPath.replace(/^\/+/, '');
  if (!withoutLeadingSlash) return directory ? '' : null;
  if (withoutLeadingSlash.includes('\\')) return null;
  for (const character of withoutLeadingSlash) {
    if (character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127) {
      return null;
    }
  }
  const segments = withoutLeadingSlash.split('/');
  if (segments.at(-1) === '') segments.pop();
  if (segments.length === 0
      || segments.some(segment =>
        !segment || segment === '.' || segment === '..')) {
    return null;
  }
  const normalized = segments.join('/');
  return directory ? `${normalized}/` : normalized;
}

function readDeepLink(search: string): FileDeepLink {
  const params = new URLSearchParams(search);
  const sortDirection: ImportTimeSortDirection =
    params.get('sort') === 'asc' ? 'asc' : 'desc';
  const requestedDirectory = params.get('path');
  const normalizedDirectory = normalizeVirtualPath(requestedDirectory, true);
  if (requestedDirectory !== null && normalizedDirectory === null) {
    return { directoryPath: '', filePath: null, sortDirection };
  }
  const directoryPath = normalizedDirectory ?? '';
  const filePath = normalizeVirtualPath(params.get('file'), false);
  const expectedParent = filePath?.includes('/')
    ? filePath.slice(0, filePath.lastIndexOf('/') + 1)
    : '';
  return {
    directoryPath,
    filePath: filePath && expectedParent === directoryPath ? filePath : null,
    sortDirection,
  };
}

function sortByImportTime(
  entries: TreeEntry[],
  direction: ImportTimeSortDirection,
): TreeEntry[] {
  return [...entries].sort((left, right) => {
    const leftTime = left.createdAt ? Date.parse(left.createdAt) : Number.NaN;
    const rightTime = right.createdAt ? Date.parse(right.createdAt) : Number.NaN;
    const leftHasTime = Number.isFinite(leftTime);
    const rightHasTime = Number.isFinite(rightTime);

    if (leftHasTime && rightHasTime && leftTime !== rightTime) {
      return direction === 'desc' ? rightTime - leftTime : leftTime - rightTime;
    }
    if (leftHasTime !== rightHasTime) {
      return leftHasTime ? -1 : 1;
    }
    return left.name.localeCompare(right.name);
  });
}

// ─── FileIcon ───────────────────────────────────────────────────────────────

function FileIcon({ entry }: { entry: TreeEntry }) {
  if (entry.type === 'directory') return <span className={styles.treeIcon}>📁</span>;
  const mime = entry.mimeType ?? '';
  if (mime === 'application/pdf') return <span className={styles.treeIcon}>📄</span>;
  if (mime.startsWith('image/')) return <span className={styles.treeIcon}>🖼️</span>;
  if (mime.startsWith('text/') || mime === 'application/json') return <span className={styles.treeIcon}>📝</span>;
  return <span className={styles.treeIcon}>📎</span>;
}

// ─── Main Component ─────────────────────────────────────────────────────────

export function Files() {
  const { t } = useTranslation();
  const { showToast } = useToast();
  const location = useLocation();
  const navigate = useNavigate();
  const deepLink = useMemo(
    () => readDeepLink(location.search),
    [location.search],
  );
  const [selectedEntry, setSelectedEntry] = useState<TreeEntry | null>(null);
  const [previewKey, setPreviewKey] = useState(0); // force preview reload
  const [dragOver, setDragOver] = useState(false);
  const [uploadState, setUploadState] = useState<'idle' | 'uploading' | 'done' | 'error'>('idle');
  const [uploadError, setUploadError] = useState('');
  const [collectionPrefix, setCollectionPrefix] = useState('');
  const [selectedCollectionKey, setSelectedCollectionKey] = useState('');
  const [embeddingState, setEmbeddingState] = useState<'idle' | 'embedding' | 'done' | 'error'>('idle');
  const [embeddingMessage, setEmbeddingMessage] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const currentPath = deepLink.directoryPath;
  const importTimeSortDirection = deepLink.sortDirection;

  // Fetch tree for current path
  const { data: treeData, isPending, error } = useQuery({
    queryKey: ['files-tree', currentPath],
    queryFn: () => filesApi.listTree(currentPath || undefined),
    staleTime: 30_000,
  });
  const { data: collectionsData } = useQuery({
    queryKey: ['files-collections'],
    queryFn: () => collectionsApi.list({ page: 0, size: 200 }),
  });
  const collections = collectionsData?.data?.collections ?? [];
  const sortedEntries = useMemo(
    () => sortByImportTime(
      treeData?.data?.entries ?? [],
      importTimeSortDirection,
    ),
    [treeData?.data?.entries, importTimeSortDirection],
  );

  useEffect(() => {
    setSelectedEntry(null);
  }, [deepLink.directoryPath, deepLink.filePath]);

  useEffect(() => {
    const targetPath = deepLink.filePath;
    if (!targetPath || isPending || !treeData?.data?.entries) return;
    const targetEntry = treeData.data.entries.find(
      entry => entry.type === 'file' && entry.path === targetPath,
    );
    if (targetEntry && selectedEntry?.path !== targetEntry.path) {
      setSelectedEntry(targetEntry);
    }
  }, [deepLink.filePath, isPending, selectedEntry?.path, treeData?.data?.entries]);

  // ── Navigation ──────────────────────────────────────────────────────────

  const navigateTo = useCallback((
    path: string,
    file?: string,
    sortDirection = importTimeSortDirection,
  ) => {
    const params = new URLSearchParams();
    if (path) params.set('path', path);
    if (file) params.set('file', file);
    if (sortDirection === 'asc') params.set('sort', 'asc');
    navigate(`/files${params.toString() ? `?${params.toString()}` : ''}`);
  }, [importTimeSortDirection, navigate]);

  const handleEntryClick = useCallback((entry: TreeEntry) => {
    if (entry.type === 'directory') {
      navigateTo(entry.path);
    } else {
      navigateTo(currentPath, entry.path);
    }
  }, [currentPath, navigateTo]);

  // ── PDF Upload ───────────────────────────────────────────────────────────

  const doImport = useCallback(async (file: File, collection?: string) => {
    setUploadState('uploading');
    setUploadError('');
    try {
      const result = await filesApi.importPdf(file, collection);
      setUploadState('done');
      showToast(t('files.importSuccess', { name: file.name, count: result.filesStored }), 'success');
      // Navigate to the parent directory and refresh
      navigateTo(result.uuid + '/');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setUploadError(msg);
      setUploadState('error');
      showToast(t('files.importError', { error: msg }), 'error');
    }
  }, [t, showToast, navigateTo]);

  const handleFilesSelected = useCallback((files: FileList | null) => {
    if (!files || files.length === 0) return;
    const pdfFile = files[0];
    if (!pdfFile.name.toLowerCase().endsWith('.pdf')) {
      showToast(t('files.onlyPdf'), 'error');
      return;
    }
    doImport(pdfFile, collectionPrefix || undefined);
  }, [collectionPrefix, doImport, showToast, t]);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    handleFilesSelected(e.dataTransfer.files);
  }, [handleFilesSelected]);

  // ── Preview ──────────────────────────────────────────────────────────────

  const handleRefresh = useCallback(() => {
    setPreviewKey(k => k + 1);
  }, []);

  const handleOpenRaw = useCallback(async () => {
    if (selectedEntry) {
      try {
        const blob = await filesApi.getRawFile(selectedEntry.path);
        const objectUrl = URL.createObjectURL(blob);
        window.open(objectUrl, '_blank', 'noopener,noreferrer');
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        showToast(t('files.previewError', { error: message }), 'error');
      }
    }
  }, [selectedEntry, showToast, t]);

  // ── Trigger Embedding ────────────────────────────────────────────────────

  const handleTriggerEmbedding = useCallback(async () => {
    // Extract UUID from current path (e.g., "uuid/" -> "uuid")
    const uuid = currentPath.replace(/\/$/, '');
    if (!uuid) return;

    setEmbeddingState('embedding');
    setEmbeddingMessage('');
    try {
      const result = await filesApi.triggerEmbedding(
        uuid,
        undefined,
        false,
        selectedCollectionKey || undefined,
      );
      setEmbeddingState('done');
      if (result.embedStatus === 'COMPLETED') {
        showToast(t('files.embedSuccess', { chunks: result.chunksCreated }), 'success');
      } else if (result.embedStatus === 'CACHED') {
        showToast(t('files.embedCached'), 'info');
      } else {
        showToast(t('files.embedFailed', { message: result.embedMessage }), 'error');
      }
      setEmbeddingMessage(result.embedMessage || '');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setEmbeddingState('error');
      setEmbeddingMessage(msg);
      showToast(t('files.embedError', { error: msg }), 'error');
    }
  }, [currentPath, selectedCollectionKey, t, showToast]);

  // ── Breadcrumb ────────────────────────────────────────────────────────────

  const breadcrumbs = pathSegments(currentPath);

  return (
    <div className={styles.container}>

      {/* ── Header ── */}
      <div className={styles.header}>
        <h1 className="page-title">{t('files.title')}</h1>

        <div className={styles.actions}>
          {/* Collection prefix input */}
          <div className={styles.collectionInput}>
            <span style={{ fontSize: '0.8rem', color: 'var(--color-text-muted, #6b7280)' }}>
              {t('files.collectionPrefix')}:
            </span>
            <input
              type="text"
              value={collectionPrefix}
              onChange={e => setCollectionPrefix(e.target.value)}
              placeholder={t('files.collectionPrefixPlaceholder')}
            />
          </div>

          {/* Upload button / area */}
          <div
            className={`${styles.uploadArea} ${dragOver ? styles.dragOver : ''}`}
            onDragOver={e => { e.preventDefault(); setDragOver(true); }}
            onDragLeave={() => setDragOver(false)}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
            title={t('files.uploadTitle')}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".pdf"
              onChange={e => handleFilesSelected(e.target.files)}
              style={{ display: 'none' }}
            />
            {uploadState === 'uploading' ? (
              <div className={styles.uploadProgress}>
                <div className={styles.uploadSpinner} />
                <span>{t('files.importing')}</span>
              </div>
            ) : uploadState === 'done' ? (
              <span className={styles.uploadDone}>✅ {t('files.importDone')}</span>
            ) : (
              <>
                <span>📤 {t('files.uploadBtn')}</span>
                <div className={styles.uploadHint}>{t('files.uploadHint')}</div>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Upload error */}
      {uploadState === 'error' && (
        <div className={styles.errorBox}>
          {t('files.importError', { error: uploadError })}
        </div>
      )}

      {/* ── Breadcrumb ── */}
      {currentPath && (
        <div className={styles.breadcrumb}>
          <span
            className={styles.breadcrumbItem}
            onClick={() => navigateTo('')}
          >
            {t('files.root')}
          </span>
          {breadcrumbs.map((seg, idx) => (
            <span key={idx} style={{ display: 'flex', alignItems: 'center', gap: '0.25rem' }}>
              <span className={styles.breadcrumbSep}>/</span>
              <span
                className={styles.breadcrumbItem}
                onClick={() => navigateTo(seg.path)}
              >
                {seg.label}
              </span>
            </span>
          ))}
        </div>
      )}

      {/* ── Add to RAG Button (shown when inside a PDF directory) ── */}
      {currentPath && !currentPath.startsWith('/papers') && (
        <div className={styles.ragActions}>
          <label className={styles.ragCollectionLabel} htmlFor="files-rag-collection">
            <span>{t('files.ragCollection')}</span>
            <select
              id="files-rag-collection"
              data-testid="files-rag-collection-select"
              value={selectedCollectionKey}
              onChange={event => setSelectedCollectionKey(event.target.value)}
              className={styles.ragCollectionSelect}
            >
              <option value="">{t('files.noCollection')}</option>
              {collections.map(collection => (
                <option
                  key={collection.collectionKey}
                  value={collection.collectionKey}
                >
                  {collection.name} ({collection.collectionKey})
                </option>
              ))}
            </select>
          </label>
          <button
            className={styles.previewBtn}
            onClick={handleTriggerEmbedding}
            disabled={embeddingState === 'embedding'}
            title={t('files.addToRagTitle')}
          >
            {embeddingState === 'embedding' ? t('files.embedding') : t('files.addToRag')}
          </button>
          {embeddingState === 'done' && embeddingMessage && (
            <span style={{ fontSize: '0.8rem', color: 'var(--color-text-muted, #6b7280)' }}>
              {embeddingMessage}
            </span>
          )}
          {embeddingState === 'error' && (
            <span className={styles.ragError}>
              {embeddingMessage}
            </span>
          )}
        </div>
      )}

      {/* ── Two-panel body ── */}
      <div className={styles.body}>

        {/* Tree panel */}
        <div className={styles.treePanel}>
          <div className={styles.treeHeader}>
            <span className={styles.treeHeaderPath} title={currentPath || t('files.root')}>
              {currentPath ? currentPath : t('files.root')}
            </span>
            <button
              type="button"
              className={styles.sortButton}
              data-testid="files-import-time-sort"
              onClick={() => navigateTo(
                currentPath,
                deepLink.filePath ?? undefined,
                importTimeSortDirection === 'desc' ? 'asc' : 'desc',
              )}
              title={t(
                importTimeSortDirection === 'desc'
                  ? 'files.sortNewestFirstTitle'
                  : 'files.sortOldestFirstTitle',
              )}
              aria-label={t(
                importTimeSortDirection === 'desc'
                  ? 'files.sortNewestFirstTitle'
                  : 'files.sortOldestFirstTitle',
              )}
            >
              <span>{t('files.sortImportedAt')}</span>
              <span aria-hidden="true">
                {importTimeSortDirection === 'desc' ? '↓' : '↑'}
              </span>
            </button>
          </div>
          <div className={styles.treeBody}>
            {isPending ? (
              <>
                {[1, 2, 3, 4].map(i => (
                  <div key={i} style={{ padding: '0.4rem 0.5rem' }}>
                    <Skeleton width="80%" height="0.875rem" />
                  </div>
                ))}
              </>
            ) : error ? (
              <div className={styles.errorBox} style={{ margin: '0.5rem' }}>
                {String(error)}
              </div>
            ) : treeData?.data?.entries?.length === 0 ? (
              <div className={styles.treeEmpty}>
                {t('files.empty')}
              </div>
            ) : (
              <>
                {/* Up one level */}
                {currentPath && (
                  <div
                    className={styles.treeItem}
                    onClick={() => {
                      const segments = currentPath.split('/').filter(Boolean);
                      const parent = segments.length > 1
                        ? segments.slice(0, -1).join('/') + '/'
                        : '';
                      navigateTo(parent);
                    }}
                  >
                    <span className={styles.treeIcon}>⬆️</span>
                    <span className={styles.treeName}>..</span>
                  </div>
                )}
                {sortedEntries.map(entry => (
                  <div
                    key={entry.path}
                    className={`${styles.treeItem} ${selectedEntry?.path === entry.path ? styles.active : ''}`}
                    onClick={() => handleEntryClick(entry)}
                    data-testid="file-tree-entry"
                    data-entry-path={entry.path}
                  >
                    <FileIcon entry={entry} />
                    <span className={styles.treeName} title={entry.name}>
                      {entry.name}
                    </span>
                    {entry.type === 'file' && (
                      <span className={styles.treeMeta}>{formatSize(entry.size)}</span>
                    )}
                  </div>
                ))}
              </>
            )}
          </div>
        </div>

        {/* Preview panel */}
        <div className={styles.previewPanel}>
          <div className={styles.previewHeader}>
            <span className={styles.previewTitle}>
              {selectedEntry ? selectedEntry.name : t('files.previewTitle')}
            </span>
            {selectedEntry && (
              <div className={styles.previewActions}>
                <button className={styles.previewBtn} onClick={handleRefresh} title={t('files.refresh')}>
                  🔄
                </button>
                <button className={styles.previewBtn} onClick={handleOpenRaw} title={t('files.openRaw')}>
                  {t('files.openRaw')}
                </button>
              </div>
            )}
          </div>

          {!selectedEntry ? (
            <div className={styles.previewEmpty}>
              <div className={styles.previewEmptyIcon}>📂</div>
              <span>{t('files.selectFile')}</span>
            </div>
          ) : selectedEntry.type === 'directory' ? (
            <div className={styles.previewEmpty}>
              <div className={styles.previewEmptyIcon}>📁</div>
              <span>{t('files.openDirectory', { name: selectedEntry.name })}</span>
            </div>
          ) : (
            <FilePreview
              entry={selectedEntry}
              reloadKey={previewKey}
            />
          )}
        </div>
      </div>
    </div>
  );
}
