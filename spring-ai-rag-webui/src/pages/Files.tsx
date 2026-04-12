import { useState, useRef, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { filesApi, type TreeEntry } from '../api/files';
import { useToast } from '../components/Toast';
import { Skeleton } from '../components/Skeleton';
import styles from './Files.module.css';

// ─── Helpers ────────────────────────────────────────────────────────────────

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function pathSegments(virtualPath: string): { label: string; path: string }[] {
  if (!virtualPath) return [];
  return virtualPath.split('/').filter(Boolean).map((segment, idx, arr) => ({
    label: segment,
    path: arr.slice(0, idx + 1).join('/') + '/',
  }));
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

// ─── PreviewContent (fetch + innerHTML, no iframe) ─────────────────────────

interface PreviewContentProps {
  entry: TreeEntry;
}

function PreviewContent({ entry }: PreviewContentProps) {
  const [html, setHtml] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const fetchPreview = useCallback(async (path: string) => {
    // Cancel any in-flight request
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    setLoading(true);
    setError(null);

    try {
      // Use the /preview/html endpoint that returns an HTML fragment (no wrapper)
      const url = `/api/v1/rag/files/preview/html?path=${encodeURIComponent(path)}`;
      const resp = await fetch(url, { signal: ctrl.signal });
      if (!resp.ok) {
        throw new Error(`HTTP ${resp.status}`);
      }
      const text = await resp.text();
      setHtml(text);
    } catch (err) {
      if ((err as Error).name === 'AbortError') return;
      setError((err as Error).message ?? 'Failed to load preview');
      setHtml(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch when entry changes
  useState(() => {
    fetchPreview(entry.path);
  });

  if (loading) {
    return (
      <div className={styles.previewLoading}>
        <div className={styles.spinner} />
        <span>Loading preview…</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className={styles.errorBox}>
        {error}
      </div>
    );
  }

  if (!html) return null;

  // Render HTML fragment via dangerouslySetInnerHTML (same-origin API, trusted content)
  return (
    <div
      className={styles.previewContent}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}

// ─── Main Component ─────────────────────────────────────────────────────────

export function Files() {
  const { t } = useTranslation();
  const { showToast } = useToast();

  const [currentPath, setCurrentPath] = useState('');
  const [selectedEntry, setSelectedEntry] = useState<TreeEntry | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [uploadState, setUploadState] = useState<'idle' | 'uploading' | 'done' | 'error'>('idle');
  const [uploadError, setUploadError] = useState('');
  const [collectionPrefix, setCollectionPrefix] = useState('');
  const [previewKey, setPreviewKey] = useState(0); // force re-render on entry change
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { data: treeData, isPending, error, refetch } = useQuery({
    queryKey: ['files-tree', currentPath],
    queryFn: () => filesApi.listTree(currentPath || undefined),
    staleTime: 30_000,
  });

  // ── Navigation ──────────────────────────────────────────────────────────

  const navigateTo = useCallback((path: string) => {
    setCurrentPath(path);
    setSelectedEntry(null);
    setPreviewKey(k => k + 1);
  }, []);

  const handleEntryClick = useCallback((entry: TreeEntry) => {
    if (entry.type === 'directory') {
      navigateTo(entry.path);
    } else {
      setSelectedEntry(entry);
      setPreviewKey(k => k + 1);
    }
  }, [navigateTo]);

  // ── PDF Upload ───────────────────────────────────────────────────────────

  const doImport = useCallback(async (file: File, collection?: string) => {
    setUploadState('uploading');
    setUploadError('');
    try {
      const result = await filesApi.importPdf(file, collection);
      setUploadState('done');
      showToast(t('files.importSuccess', { name: file.name, count: result.filesImported }), 'success');
      const parentPath = result.virtualRoot.substring(0, result.virtualRoot.lastIndexOf('/') + 1);
      setCurrentPath(parentPath);
      refetch();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setUploadError(msg);
      setUploadState('error');
      showToast(t('files.importError', { error: msg }), 'error');
    }
  }, [t, showToast, refetch]);

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

  // ── Breadcrumb ───────────────────────────────────────────────────────────

  const breadcrumbs = pathSegments(currentPath);

  return (
    <div className={styles.container}>

      {/* ── Header ── */}
      <div className={styles.header}>
        <h1 className="page-title">{t('files.title')}</h1>

        <div className={styles.actions}>
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
                <div className={styles.spinner} />
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

      {/* ── Two-panel body ── */}
      <div className={styles.body}>

        {/* Tree panel */}
        <div className={styles.treePanel}>
          <div className={styles.treeHeader}>
            {currentPath ? currentPath : t('files.root')}
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
              <div className={styles.emptyTree}>
                {t('files.empty')}
              </div>
            ) : (
              <>
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
                {treeData?.data?.entries?.map(entry => (
                  <div
                    key={entry.path}
                    className={`${styles.treeItem} ${selectedEntry?.path === entry.path ? styles.active : ''}`}
                    onClick={() => handleEntryClick(entry)}
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
                <button
                  className={styles.previewBtn}
                  onClick={() => {
                    setPreviewKey(k => k + 1);
                    // Re-trigger fetch by toggling selectedEntry
                    const entry = selectedEntry;
                    setSelectedEntry(null);
                    setTimeout(() => setSelectedEntry(entry), 0);
                  }}
                  title={t('files.refresh')}
                >
                  🔄
                </button>
                <button
                  className={styles.previewBtn}
                  onClick={() => window.open(filesApi.rawFileUrl(selectedEntry.path), '_blank')}
                  title={t('files.openRaw')}
                >
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
            <div key={previewKey} className={styles.previewBody}>
              <PreviewContent entry={selectedEntry} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
