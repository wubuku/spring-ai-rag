import { useState, useRef, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { filesApi, type TreeEntry } from '../api/files';
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

  // Current directory path (trailing slash for directories)
  const [currentPath, setCurrentPath] = useState('');
  const [selectedEntry, setSelectedEntry] = useState<TreeEntry | null>(null);
  const [previewKey, setPreviewKey] = useState(0); // force preview reload
  const [dragOver, setDragOver] = useState(false);
  const [uploadState, setUploadState] = useState<'idle' | 'uploading' | 'done' | 'error'>('idle');
  const [uploadError, setUploadError] = useState('');
  const [collectionPrefix, setCollectionPrefix] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Fetch tree for current path
  const { data: treeData, isPending, error, refetch } = useQuery({
    queryKey: ['files-tree', currentPath],
    queryFn: () => filesApi.listTree(currentPath || undefined),
    staleTime: 30_000,
  });

  // ── Navigation ──────────────────────────────────────────────────────────

  const navigateTo = useCallback((path: string) => {
    setCurrentPath(path);
    setSelectedEntry(null);
  }, []);

  const handleEntryClick = useCallback((entry: TreeEntry) => {
    if (entry.type === 'directory') {
      navigateTo(entry.path);
    } else {
      setSelectedEntry(entry);
    }
  }, [navigateTo]);

  // ── PDF Upload ───────────────────────────────────────────────────────────

  const doImport = useCallback(async (file: File, collection?: string) => {
    setUploadState('uploading');
    setUploadError('');
    try {
      const result = await filesApi.importPdf(file, collection);
      setUploadState('done');
      showToast(t('files.importSuccess', { name: file.name, count: result.filesStored }), 'success');
      // Navigate to the parent directory and refresh
      setCurrentPath(result.uuid + '/');
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

  // ── Preview ──────────────────────────────────────────────────────────────

  const handleRefresh = useCallback(() => {
    setPreviewKey(k => k + 1);
  }, []);

  const handleOpenRaw = useCallback(() => {
    if (selectedEntry) {
      window.open(filesApi.rawFileUrl(selectedEntry.path), '_blank');
    }
  }, [selectedEntry]);

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
              <div style={{ padding: '1rem', textAlign: 'center', color: 'var(--color-text-muted)', fontSize: '0.8rem' }}>
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