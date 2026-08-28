import {
  useState,
  useRef,
  useCallback,
  useMemo,
  useEffect,
} from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { filesApi, type TreeEntry } from '../api/files';
import { collectionsApi } from '../api/collections';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import { FilePreview } from '../components/FilePreview/FilePreview';
import {
  readWorkspaceState,
  rememberRoute,
  writeWorkspaceState,
} from '../utils/workspaceState';
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

function parentPath(virtualPath: string): string {
  const segments = virtualPath.split('/').filter(Boolean);
  return segments.length > 1
    ? segments.slice(0, -1).join('/') + '/'
    : '';
}

type ImportTimeSortDirection = 'desc' | 'asc';

interface FileDeepLink {
  directoryPath: string;
  filePath: string | null;
  sortDirection: ImportTimeSortDirection;
  query: string;
}

const FILES_COLLECTION_STATE_KEY = 'files-rag-collection';
const FILES_LAYOUT_STATE_KEY = 'files-layout';
const MIN_TREE_PANEL_WIDTH = 240;
const MAX_TREE_PANEL_WIDTH = 560;
const MIN_PREVIEW_PANEL_WIDTH = 320;
const SPLITTER_WIDTH = 9;
const DEFAULT_TREE_PANEL_WIDTH = 320;

function isFilesCollectionState(value: unknown): value is { collectionKey: string } {
  return Boolean(value)
    && typeof value === 'object'
    && typeof (value as { collectionKey?: unknown }).collectionKey === 'string'
    && (value as { collectionKey: string }).collectionKey.length <= 256;
}

function isFilesLayoutState(value: unknown): value is { treePanelWidth: number } {
  const width = (value as { treePanelWidth?: unknown } | null)?.treePanelWidth;
  return Number.isInteger(width)
    && Number(width) >= MIN_TREE_PANEL_WIDTH
    && Number(width) <= MAX_TREE_PANEL_WIDTH;
}

function clampTreePanelWidth(width: number, availableWidth?: number): number {
  const availableMaximum = availableWidth === undefined
    ? MAX_TREE_PANEL_WIDTH
    : Math.max(
        MIN_TREE_PANEL_WIDTH,
        availableWidth - MIN_PREVIEW_PANEL_WIDTH - SPLITTER_WIDTH,
      );
  return Math.min(
    Math.max(Math.round(width), MIN_TREE_PANEL_WIDTH),
    Math.min(MAX_TREE_PANEL_WIDTH, availableMaximum),
  );
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
    return {
      directoryPath: '',
      filePath: null,
      sortDirection,
      query: params.get('q')?.trim().slice(0, 256) ?? '',
    };
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
    query: params.get('q')?.trim().slice(0, 256) ?? '',
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
    return (left.displayName || left.name).localeCompare(
      right.displayName || right.name,
    );
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
  const queryClient = useQueryClient();
  const deepLink = useMemo(
    () => readDeepLink(location.search),
    [location.search],
  );
  const [selectedEntry, setSelectedEntry] = useState<TreeEntry | null>(null);
  const [previewKey, setPreviewKey] = useState(0); // force preview reload
  const [dragOver, setDragOver] = useState(false);
  const [uploadState, setUploadState] = useState<'idle' | 'uploading' | 'done' | 'error'>('idle');
  const [uploadError, setUploadError] = useState('');
  const [selectedCollectionKey, setSelectedCollectionKey] = useState(
    () => readWorkspaceState(FILES_COLLECTION_STATE_KEY, isFilesCollectionState)
      ?.collectionKey ?? '',
  );
  const [treePanelWidth, setTreePanelWidth] = useState(
    () => readWorkspaceState(FILES_LAYOUT_STATE_KEY, isFilesLayoutState)
      ?.treePanelWidth ?? DEFAULT_TREE_PANEL_WIDTH,
  );
  const [queryDraft, setQueryDraft] = useState(deepLink.query);
  const [isResizingTree, setIsResizingTree] = useState(false);
  const [embeddingState, setEmbeddingState] = useState<'idle' | 'embedding' | 'done' | 'error'>('idle');
  const [embeddingMessage, setEmbeddingMessage] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const queryCompositionRef = useRef(false);
  const previousUrlQueryRef = useRef(deepLink.query);
  const resizeStateRef = useRef<{
    pointerId: number;
    startX: number;
    startWidth: number;
  } | null>(null);
  const currentPath = deepLink.directoryPath;
  const importTimeSortDirection = deepLink.sortDirection;
  const activeImportId = currentPath.split('/').filter(Boolean)[0] ?? '';
  const selectedDirectoryImportId = selectedEntry?.type === 'directory'
    ? selectedEntry.importId || selectedEntry.name
    : '';
  const ragImportId = selectedEntry?.type === 'directory' && !currentPath
    ? selectedDirectoryImportId
    : activeImportId;
  const canAddToRag = Boolean(ragImportId && ragImportId !== 'papers');

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
  const visibleEntries = useMemo(() => {
    const normalizedQuery = deepLink.query.toLocaleLowerCase();
    const entries = treeData?.data?.entries ?? [];
    const filtered = normalizedQuery
      ? entries.filter(entry => [
          entry.displayName,
          entry.originalFilename,
          entry.importId,
          entry.name,
          entry.path,
        ].some(value => value?.toLocaleLowerCase().includes(normalizedQuery)))
      : entries;
    return sortByImportTime(filtered, importTimeSortDirection);
  }, [deepLink.query, importTimeSortDirection, treeData?.data?.entries]);

  useEffect(() => {
    writeWorkspaceState(FILES_COLLECTION_STATE_KEY, {
      collectionKey: selectedCollectionKey,
    });
  }, [selectedCollectionKey]);

  useEffect(() => {
    writeWorkspaceState(FILES_LAYOUT_STATE_KEY, { treePanelWidth });
  }, [treePanelWidth]);

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
    query = deepLink.query,
    replace = false,
  ) => {
    const params = new URLSearchParams();
    if (path) params.set('path', path);
    if (file) params.set('file', file);
    if (sortDirection === 'asc') params.set('sort', 'asc');
    if (query) params.set('q', query);
    const search = params.toString() ? `?${params.toString()}` : '';
    rememberRoute('/files', search);
    navigate(`/files${search}`, { replace });
  }, [deepLink.query, importTimeSortDirection, navigate]);

  const commitQuery = useCallback((query: string) => {
    navigateTo(
      currentPath,
      deepLink.filePath ?? undefined,
      importTimeSortDirection,
      query.trim().slice(0, 256),
      true,
    );
  }, [currentPath, deepLink.filePath, importTimeSortDirection, navigateTo]);

  useEffect(() => {
    if (deepLink.query === previousUrlQueryRef.current) return;
    previousUrlQueryRef.current = deepLink.query;
    if (!queryCompositionRef.current) {
      setQueryDraft(deepLink.query);
    }
  }, [deepLink.query]);

  const handleQueryCompositionStart = useCallback(() => {
    queryCompositionRef.current = true;
  }, []);

  const handleQueryCompositionEnd = useCallback((
    event: React.CompositionEvent<HTMLInputElement>,
  ) => {
    queryCompositionRef.current = false;
    const value = event.currentTarget.value.slice(0, 256);
    setQueryDraft(value);
    commitQuery(value);
  }, [commitQuery]);

  const availableTreePanelMaximum = useCallback(() => {
    const measuredWidth = bodyRef.current?.getBoundingClientRect().width ?? 0;
    const availableWidth = measuredWidth > 0 ? measuredWidth : undefined;
    return clampTreePanelWidth(MAX_TREE_PANEL_WIDTH, availableWidth);
  }, []);

  const handleSplitterPointerDown = useCallback((
    event: React.PointerEvent<HTMLDivElement>,
  ) => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    resizeStateRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: treePanelWidth,
    };
    setIsResizingTree(true);
  }, [treePanelWidth]);

  const handleSplitterPointerMove = useCallback((
    event: React.PointerEvent<HTMLDivElement>,
  ) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;
    const measuredWidth = bodyRef.current?.getBoundingClientRect().width ?? 0;
    const availableWidth = measuredWidth > 0 ? measuredWidth : undefined;
    setTreePanelWidth(clampTreePanelWidth(
      resizeState.startWidth + event.clientX - resizeState.startX,
      availableWidth,
    ));
  }, []);

  const stopTreeResize = useCallback((
    event: React.PointerEvent<HTMLDivElement>,
  ) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    resizeStateRef.current = null;
    setIsResizingTree(false);
  }, []);

  const handleSplitterKeyDown = useCallback((
    event: React.KeyboardEvent<HTMLDivElement>,
  ) => {
    const step = event.shiftKey ? 48 : 16;
    let nextWidth: number | null = null;
    if (event.key === 'ArrowLeft') nextWidth = treePanelWidth - step;
    if (event.key === 'ArrowRight') nextWidth = treePanelWidth + step;
    if (event.key === 'Home') nextWidth = MIN_TREE_PANEL_WIDTH;
    if (event.key === 'End') nextWidth = availableTreePanelMaximum();
    if (nextWidth === null) return;
    event.preventDefault();
    const measuredWidth = bodyRef.current?.getBoundingClientRect().width ?? 0;
    setTreePanelWidth(clampTreePanelWidth(
      nextWidth,
      measuredWidth > 0 ? measuredWidth : undefined,
    ));
  }, [availableTreePanelMaximum, treePanelWidth]);

  const selectEntry = useCallback((entry: TreeEntry) => {
    setSelectedEntry(entry);
    if (entry.type === 'file') {
      navigateTo(currentPath, entry.path);
    } else if (deepLink.filePath) {
      navigateTo(
        currentPath,
        undefined,
        importTimeSortDirection,
        deepLink.query,
        true,
      );
    }
  }, [
    currentPath,
    deepLink.filePath,
    deepLink.query,
    importTimeSortDirection,
    navigateTo,
  ]);

  const openEntry = useCallback((entry: TreeEntry) => {
    if (entry.type === 'directory') {
      navigateTo(
        entry.path,
        undefined,
        importTimeSortDirection,
        '',
      );
      return;
    }
    selectEntry(entry);
  }, [importTimeSortDirection, navigateTo, selectEntry]);

  // ── PDF Upload ───────────────────────────────────────────────────────────

  const doImport = useCallback(async (file: File) => {
    setUploadState('uploading');
    setUploadError('');
    try {
      const result = await filesApi.importPdf(file);
      setUploadState('done');
      showToast(t('files.importSuccess', { name: file.name, count: result.filesStored }), 'success');
      await queryClient.invalidateQueries({
        queryKey: ['files-tree'],
        refetchType: 'all',
      });
      navigateTo(result.uuid + '/');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setUploadError(msg);
      setUploadState('error');
      showToast(t('files.importError', { error: msg }), 'error');
    }
  }, [navigateTo, queryClient, showToast, t]);

  const handleFilesSelected = useCallback((files: FileList | null) => {
    if (!files || files.length === 0) return;
    const pdfFile = files[0];
    if (!pdfFile.name.toLowerCase().endsWith('.pdf')) {
      showToast(t('files.onlyPdf'), 'error');
      return;
    }
    doImport(pdfFile);
  }, [doImport, showToast, t]);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    handleFilesSelected(e.dataTransfer.files);
  }, [handleFilesSelected]);

  // ── Preview ──────────────────────────────────────────────────────────────

  const handleRefresh = useCallback(() => {
    setPreviewKey(k => k + 1);
  }, []);

  const handleRefreshDirectory = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ['files-tree', currentPath] });
  }, [currentPath, queryClient]);

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
    if (!canAddToRag) return;

    setEmbeddingState('embedding');
    setEmbeddingMessage('');
    try {
      const result = await filesApi.triggerEmbedding(
        ragImportId,
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
  }, [canAddToRag, ragImportId, selectedCollectionKey, t, showToast]);

  // ── Breadcrumb ────────────────────────────────────────────────────────────

  const breadcrumbs = pathSegments(currentPath);
  const importMetadata = treeData?.data?.importMetadata;
  const rootImportId = activeImportId;
  const rootDisplayName = importMetadata
    && importMetadata.importId === rootImportId
    ? importMetadata.displayName
    : null;
  const currentDirectoryLabel = rootDisplayName
    || breadcrumbs.at(-1)?.label
    || t('files.root');
  const currentDirectoryPathLabel = currentPath || '/';

  const handleCopyImportId = useCallback(async (
    event: React.MouseEvent,
    importId: string,
  ) => {
    event.stopPropagation();
    try {
      await navigator.clipboard.writeText(importId);
      showToast(t('files.importIdCopied'), 'success');
    } catch {
      showToast(t('files.importIdCopyFailed'), 'error');
    }
  }, [showToast, t]);

  return (
    <div className={styles.container}>

      {/* ── Header ── */}
      <div className={styles.header}>
        <h1 className="page-title">{t('files.title')}</h1>

        <div className={styles.actions}>
          {/* Upload button / area */}
          <div
            role="button"
            tabIndex={0}
            className={`${styles.uploadArea} ${dragOver ? styles.dragOver : ''}`}
            onDragOver={e => { e.preventDefault(); setDragOver(true); }}
            onDragLeave={() => setDragOver(false)}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                fileInputRef.current?.click();
              }
            }}
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
              <span>📤 {t('files.uploadBtn')}</span>
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

      {/* Stable location and general file-manager controls. */}
      <div className={styles.commandBar} data-testid="files-command-bar">
        <div className={styles.locationGroup}>
          <button
            type="button"
            className={styles.iconButton}
            onClick={() => navigateTo(
              parentPath(currentPath),
              undefined,
              importTimeSortDirection,
              '',
            )}
            disabled={!currentPath}
            title={t('files.goUp')}
            aria-label={t('files.goUp')}
          >
            ↑
          </button>
          <nav className={styles.breadcrumb} aria-label={t('files.location')}>
            <button
              type="button"
              className={styles.breadcrumbItem}
              onClick={() => navigateTo(
                '',
                undefined,
                importTimeSortDirection,
                '',
              )}
              title={t('files.root')}
              aria-current={!currentPath ? 'page' : undefined}
            >
              {t('files.root')}
            </button>
            {breadcrumbs.map((seg, idx) => (
              <span key={seg.path} className={styles.breadcrumbSegment}>
                <span className={styles.breadcrumbSep} aria-hidden="true">/</span>
                <button
                  type="button"
                  className={styles.breadcrumbItem}
                  onClick={() => navigateTo(
                    seg.path,
                    undefined,
                    importTimeSortDirection,
                    '',
                  )}
                  title={idx === 0 && rootDisplayName ? rootImportId : seg.label}
                  aria-current={idx === breadcrumbs.length - 1 ? 'page' : undefined}
                >
                  {idx === 0 && rootDisplayName ? rootDisplayName : seg.label}
                </button>
              </span>
            ))}
          </nav>
        </div>
        <div className={styles.locationActions}>
          <label className={styles.searchControl} htmlFor="files-query">
            <span className={styles.searchIcon} aria-hidden="true">⌕</span>
            <span className={styles.visuallyHidden}>{t('files.searchLabel')}</span>
            <input
              id="files-query"
              type="search"
              aria-label={t('files.searchLabel')}
              value={queryDraft}
              placeholder={t('files.searchPlaceholder')}
              onChange={event => {
                const value = event.target.value.slice(0, 256);
                setQueryDraft(value);
                if (!queryCompositionRef.current
                    && !(event.nativeEvent as InputEvent).isComposing) {
                  commitQuery(value);
                }
              }}
              onCompositionStart={handleQueryCompositionStart}
              onCompositionEnd={handleQueryCompositionEnd}
              onBlur={() => {
                if (!queryCompositionRef.current
                    && queryDraft.trim() !== deepLink.query) {
                  commitQuery(queryDraft);
                }
              }}
            />
          </label>
          <button
            type="button"
            className={styles.iconButton}
            onClick={handleRefreshDirectory}
            title={t('files.refreshDirectory')}
            aria-label={t('files.refreshDirectory')}
          >
            ↻
          </button>
        </div>
      </div>

      {/* ── Two-panel body ── */}
      <div
        ref={bodyRef}
        className={`${styles.body} ${isResizingTree ? styles.resizing : ''}`}
        data-testid="files-workspace"
      >

        {/* Tree panel */}
        <section
          className={styles.treePanel}
          data-testid="files-tree-panel"
          aria-label={t('files.directoryList')}
          style={{ width: treePanelWidth, flexBasis: treePanelWidth }}
        >
            <div className={styles.treeHeader}>
              <div className={styles.treeHeaderIdentity}>
                <span className={styles.treeHeaderTitle}>{t('files.contents')}</span>
              <span
                className={styles.treeHeaderPath}
                title={currentDirectoryPathLabel}
              >
                  {currentDirectoryLabel}
                </span>
              </div>
              <span className={styles.listCount} aria-live="polite">
                {visibleEntries.length} {t('files.items')}
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
          <div className={styles.listHeader} aria-hidden="true">
            <span>{t('files.nameColumn')}</span>
            <span>{t('files.typeColumn')}</span>
            <span>{t('files.detailsColumn')}</span>
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
            ) : (
              <>
                {/* Up one level */}
                {currentPath && (
                  <button
                    type="button"
                    className={`${styles.treeEntryButton} ${styles.parentEntryButton}`}
                    onClick={() => navigateTo(
                      parentPath(currentPath),
                      undefined,
                      importTimeSortDirection,
                      '',
                    )}
                    data-testid="files-parent-entry"
                  >
                    <span className={styles.treeNameCell}>
                      <span className={styles.treeIcon} aria-hidden="true">⬆️</span>
                      <span className={styles.treeIdentity}>
                        <span className={styles.treeName}>{t('files.goUp')}</span>
                      </span>
                    </span>
                  </button>
                )}
                {visibleEntries.length === 0 ? (
                  <div className={styles.treeEmpty}>
                    {deepLink.query ? t('files.noMatches') : t('files.empty')}
                  </div>
                ) : visibleEntries.map(entry => {
                  const primaryName = entry.displayName || entry.name;
                  const importId = entry.importId || (
                    entry.type === 'directory' ? entry.name : null
                  );
                  const showImportIdentity = entry.type === 'directory'
                    && Boolean(entry.displayName)
                    && Boolean(importId);
                  return (
                  <div
                    key={entry.path}
                    className={`${styles.treeItem} ${selectedEntry?.path === entry.path ? styles.active : ''}`}
                    data-testid="file-tree-entry"
                    data-entry-path={entry.path}
                  >
                    <button
                      type="button"
                      className={styles.treeEntryButton}
                      onClick={() => selectEntry(entry)}
                      onDoubleClick={() => openEntry(entry)}
                      onKeyDown={event => {
                        if (event.key === 'Enter') {
                          event.preventDefault();
                          openEntry(entry);
                        }
                      }}
                      title={entry.originalFilename || primaryName}
                    >
                      <span className={styles.treeNameCell}>
                        <FileIcon entry={entry} />
                        <span className={styles.treeIdentity}>
                          <span className={styles.treeName}>
                            {primaryName}
                          </span>
                          {showImportIdentity && (
                            <span className={styles.importIdentity}>
                              <span title={t('files.importId')}>
                                {importId}
                              </span>
                            </span>
                          )}
                        </span>
                      </span>
                      <span className={styles.entryType}>
                        {entry.type === 'directory'
                          ? t('files.folder')
                          : entry.mimeType || t('files.file')}
                      </span>
                      <span className={styles.treeMeta}>
                        {entry.type === 'file' ? formatSize(entry.size) : '—'}
                      </span>
                    </button>
                    {showImportIdentity && (
                      <button
                        type="button"
                        className={styles.copyImportId}
                        onClick={event => handleCopyImportId(event, importId!)}
                        aria-label={t('files.copyImportId', { id: importId })}
                        title={t('files.copyImportId', { id: importId })}
                      >
                        ⧉
                      </button>
                    )}
                  </div>
                  );
                })}
              </>
            )}
          </div>
        </section>

        <div
          className={styles.splitter}
          data-testid="files-tree-splitter"
          role="separator"
          aria-label={t('files.resizeDirectoryList')}
          aria-orientation="vertical"
          aria-valuemin={MIN_TREE_PANEL_WIDTH}
          aria-valuemax={availableTreePanelMaximum()}
          aria-valuenow={treePanelWidth}
          tabIndex={0}
          onPointerDown={handleSplitterPointerDown}
          onPointerMove={handleSplitterPointerMove}
          onPointerUp={stopTreeResize}
          onPointerCancel={stopTreeResize}
          onKeyDown={handleSplitterKeyDown}
        >
          <span className={styles.splitterGrip} aria-hidden="true" />
        </div>

        {/* Preview panel */}
        <section
          className={styles.previewPanel}
          data-testid="files-preview-panel"
          aria-label={t('files.previewTitle')}
        >
          <div className={styles.previewHeader}>
            <span className={styles.previewTitle}>
              {selectedEntry
                ? selectedEntry.displayName || selectedEntry.name
                : t('files.previewTitle')}
            </span>
            {selectedEntry && (
              <div className={styles.previewActions}>
                <button
                  type="button"
                  className={styles.iconButton}
                  onClick={handleRefresh}
                  title={t('files.refresh')}
                  aria-label={t('files.refresh')}
                >
                  🔄
                </button>
                <button
                  type="button"
                  className={styles.previewBtn}
                  onClick={handleOpenRaw}
                  title={t('files.openRaw')}
                >
                  {t('files.openRaw')}
                </button>
              </div>
            )}
          </div>

          {!selectedEntry && currentPath ? (
            <div className={styles.folderOverview}>
              <div className={styles.folderOverviewMain}>
                <div className={styles.previewEmptyIcon}>📁</div>
                <div className={styles.folderOverviewCopy}>
                  <strong>{currentDirectoryLabel}</strong>
                  <span>{currentDirectoryPathLabel}</span>
                  {importMetadata && (
                    <span>{importMetadata.fileCount} {t('files.items')}</span>
                  )}
                </div>
              </div>
              <div className={styles.detailGrid}>
                <span>{t('files.typeColumn')}</span>
                <strong>{t('files.folder')}</strong>
                <span>{t('files.location')}</span>
                <strong title={currentDirectoryPathLabel}>{currentDirectoryPathLabel}</strong>
              </div>
              {canAddToRag && (
                <div className={styles.ragActions} data-testid="files-rag-actions">
                  <div className={styles.ragActionCopy}>
                    <strong>{t('files.ragCollection')}</strong>
                    <span>{t('files.ragDescription')}</span>
                  </div>
                  <label className={styles.ragCollectionLabel} htmlFor="files-rag-collection">
                    <span className={styles.visuallyHidden}>{t('files.ragCollection')}</span>
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
                    type="button"
                    className={styles.previewBtn}
                    onClick={handleTriggerEmbedding}
                    disabled={embeddingState === 'embedding'}
                    title={t('files.addToRagTitle')}
                  >
                    {embeddingState === 'embedding' ? t('files.embedding') : t('files.addToRag')}
                  </button>
                  <span
                    className={`${styles.commandStatus} ${
                      embeddingState === 'error' ? styles.ragError : ''
                    }`}
                    role={embeddingState === 'error' ? 'alert' : 'status'}
                  >
                    {embeddingMessage}
                  </span>
                </div>
              )}
            </div>
          ) : !selectedEntry ? (
            <div className={styles.previewEmpty}>
              <div className={styles.previewEmptyIcon}>📂</div>
              <span>{t('files.selectFile')}</span>
            </div>
          ) : selectedEntry.type === 'directory' ? (
            <div className={styles.folderOverview}>
              <div className={styles.folderOverviewMain}>
                <div className={styles.previewEmptyIcon}>📁</div>
                <div className={styles.folderOverviewCopy}>
                  <strong>{selectedEntry.displayName || selectedEntry.name}</strong>
                  <span>{selectedEntry.path}</span>
                  {selectedEntry.importId && (
                    <span>{selectedEntry.importId}</span>
                  )}
                </div>
              </div>
              <div className={styles.detailGrid}>
                <span>{t('files.typeColumn')}</span>
                <strong>{t('files.folder')}</strong>
                <span>{t('files.location')}</span>
                <strong title={selectedEntry.path}>{selectedEntry.path}</strong>
              </div>
              <button
                type="button"
                className={styles.previewBtn}
                onClick={() => openEntry(selectedEntry)}
                title={t('files.openFolderTitle')}
              >
                {t('files.openFolder')}
              </button>
              {canAddToRag && (
                <div className={styles.ragActions} data-testid="files-rag-actions">
                  <div className={styles.ragActionCopy}>
                    <strong>{t('files.ragCollection')}</strong>
                    <span>{t('files.ragDescription')}</span>
                  </div>
                  <label className={styles.ragCollectionLabel} htmlFor="files-rag-collection">
                    <span className={styles.visuallyHidden}>{t('files.ragCollection')}</span>
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
                    type="button"
                    className={styles.previewBtn}
                    onClick={handleTriggerEmbedding}
                    disabled={embeddingState === 'embedding'}
                    title={t('files.addToRagTitle')}
                  >
                    {embeddingState === 'embedding' ? t('files.embedding') : t('files.addToRag')}
                  </button>
                  <span
                    className={`${styles.commandStatus} ${
                      embeddingState === 'error' ? styles.ragError : ''
                    }`}
                    role={embeddingState === 'error' ? 'alert' : 'status'}
                  >
                    {embeddingMessage}
                  </span>
                </div>
              )}
            </div>
          ) : (
            <FilePreview
              entry={selectedEntry}
              reloadKey={previewKey}
            />
          )}
        </section>
      </div>
    </div>
  );
}
