import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { documentsApi } from '../../api/documents';
import type { DocumentVersion } from '../../api/documents';
import { computeLineDiff, truncateForPreview } from './diffUtils';
import styles from './VersionHistoryModal.module.css';

interface VersionHistoryModalProps {
  documentId: number;
  documentTitle: string;
  onClose: () => void;
}

type DiffLine = { type: 'equal'; value: string } | { type: 'insert'; value: string } | { type: 'delete'; value: string };

function ChangeTypeBadge({ changeType }: { changeType: string }) {
  const cls =
    changeType === 'CREATE'
      ? styles.changeTypeCreate
      : changeType === 'UPDATE'
        ? styles.changeTypeUpdate
        : changeType === 'FORCE_REEMBED'
          ? styles.changeTypeForceReembed
          : styles.changeTypeUnknown;
  return <span className={`${styles.changeType} ${cls}`}>{changeType}</span>;
}

export function VersionHistoryModal({ documentId, documentTitle, onClose }: VersionHistoryModalProps) {
  const { t } = useTranslation();
  const [tab, setTab] = useState<'list' | 'diff'>('list');
  const [page, setPage] = useState(0);
  const PAGE_SIZE = 20;
  const [compareA, setCompareA] = useState<DocumentVersion | null>(null);
  const [compareB, setCompareB] = useState<DocumentVersion | null>(null);
  const [diffLines, setDiffLines] = useState<DiffLine[] | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  const { data, isPending, error } = useQuery({
    queryKey: ['document-versions', documentId, page],
    queryFn: () => documentsApi.getVersions(documentId, page, PAGE_SIZE),
    staleTime: 30000,
  });

  const handleCompare = async () => {
    if (!compareA || !compareB) return;
    setDiffLoading(true);
    try {
      const [vA, vB] = await Promise.all([
        documentsApi.getVersion(documentId, compareA.versionNumber),
        documentsApi.getVersion(documentId, compareB.versionNumber),
      ]);
      const vAd = vA.data;
      const vBd = vB.data;
      const older = vAd.versionNumber < vBd.versionNumber ? vAd : vBd;
      const newer = vAd.versionNumber < vBd.versionNumber ? vBd : vAd;
      const lines = computeLineDiff(
        truncateForPreview(older.contentSnapshot ?? ''),
        truncateForPreview(newer.contentSnapshot ?? '')
      );
      setDiffLines(lines);
      setTab('diff');
    } finally {
      setDiffLoading(false);
    }
  };

  const handleSelectForCompare = (v: DocumentVersion) => {
    if (!compareA) {
      setCompareA(v);
    } else if (!compareB && compareA.id !== v.id) {
      setCompareB(v);
    } else if (compareA.id === v.id) {
      setCompareA(null);
    } else if (compareB && compareB.id !== v.id) {
      setCompareA(v);
      setCompareB(null);
    }
  };

  // Keyboard close on Escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  const totalPages = data ? Math.ceil(data.data.totalVersions / PAGE_SIZE) : 0;

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <div className={styles.modalHeader}>
          <h2 className={styles.modalTitle}>
            {t('versions.title', 'Version History')} — {documentTitle}
          </h2>
          <button className={styles.closeBtn} onClick={onClose} aria-label={t('common.close')}>
            ×
          </button>
        </div>

        <div className={styles.tabs}>
          <button
            className={`${styles.tab} ${tab === 'list' ? styles.tabActive : ''}`}
            onClick={() => setTab('list')}
          >
            {t('versions.listTab', 'Version List')}
          </button>
          <button
            className={`${styles.tab} ${tab === 'diff' ? styles.tabActive : ''}`}
            onClick={() => setTab('diff')}
            disabled={!diffLines}
          >
            {t('versions.diffTab', 'Diff View')}
          </button>
        </div>

        <div className={styles.body}>
          {/* ── LIST TAB ── */}
          {tab === 'list' && (
            <>
              {isPending && <div className={styles.loading}>{t('common.loading')}</div>}
              {error && (
                <div className={styles.error}>
                  {t('versions.loadError', 'Failed to load version history')}:{' '}
                  {error instanceof Error ? error.message : String(error)}
                </div>
              )}

              {data && (
                <>
                  {/* Compare bar */}
                  <div className={styles.compareBar}>
                    <span>
                      {compareA && (
                        <>
                          <strong>v{compareA.versionNumber}</strong>
                          {compareB && (
                            <>
                              {' '}
                              vs <strong>v{compareB.versionNumber}</strong>
                            </>
                          )}
                        </>
                      )}
                      {!compareA && <span>{t('versions.selectTwo', 'Select two versions to compare')}</span>}
                    </span>
                    <button
                      className={styles.compareBtn}
                      onClick={handleCompare}
                      disabled={!compareA || !compareB || diffLoading}
                    >
                      {diffLoading ? t('common.loading') : t('versions.compare', 'Compare')}
                    </button>
                  </div>

                  {data.data.versions.length === 0 ? (
                    <div className={styles.empty}>{t('versions.noVersions', 'No version history found')}</div>
                  ) : (
                    <div className={styles.versionList}>
                      {data.data.versions.map(v => {
                        const isSelectedA = compareA?.id === v.id;
                        const isSelectedB = compareB?.id === v.id;
                        return (
                          <div
                            key={v.id}
                            className={`${styles.versionItem} ${isSelectedA || isSelectedB ? styles.versionItemSelected : ''} ${styles.compareMode}`}
                            onClick={() => handleSelectForCompare(v)}
                          >
                            <input
                              type="checkbox"
                              className={styles.versionRadio}
                              checked={isSelectedA || isSelectedB}
                              readOnly
                              aria-label={`Version ${v.versionNumber}`}
                            />
                            <div className={styles.versionInfo}>
                              <div className={styles.versionMeta}>
                                <span className={styles.versionNumber}>v{v.versionNumber}</span>
                                <ChangeTypeBadge changeType={v.changeType} />
                              </div>
                              {v.changeDescription && (
                                <div className={styles.changeDesc}>{v.changeDescription}</div>
                              )}
                              <div className={styles.versionDate}>
                                {new Date(v.createdAt).toLocaleString()}
                              </div>
                            </div>
                            <span className={styles.versionHash}>{v.contentHash?.slice(0, 8)}…</span>
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {totalPages > 1 && (
                    <div className={styles.pagination}>
                      <button
                        className={styles.pageBtn}
                        onClick={() => setPage(p => p - 1)}
                        disabled={page === 0}
                      >
                        {t('common.previous')}
                      </button>
                      <span className={styles.pageInfo}>
                        {t('versions.page', 'Page {{page}} of {{total}}', {
                          page: page + 1,
                          total: totalPages,
                        })}
                      </span>
                      <button
                        className={styles.pageBtn}
                        onClick={() => setPage(p => p + 1)}
                        disabled={page >= totalPages - 1}
                      >
                        {t('common.next')}
                      </button>
                    </div>
                  )}
                </>
              )}
            </>
          )}

          {/* ── DIFF TAB ── */}
          {tab === 'diff' && diffLines && (
            <>
              <div className={styles.diffStats}>
                <span className={styles.diffStatsInsert}>
                  +{diffLines.filter(l => l.type === 'insert').length}{' '}
                  {t('versions.inserted', 'inserted')}
                </span>
                <span className={styles.diffStatsDelete}>
                  -{diffLines.filter(l => l.type === 'delete').length}{' '}
                  {t('versions.deleted', 'deleted')}
                </span>
              </div>
              <div className={styles.diffView}>
                {diffLines.map((line, idx) => (
                  <div
                    key={idx}
                    className={`${styles.diffLine} ${
                      line.type === 'insert'
                        ? styles.diffInsert
                        : line.type === 'delete'
                          ? styles.diffDelete
                          : styles.diffEqual
                    }`}
                  >
                    <span className={styles.diffLineNum}>{idx + 1}</span>
                    <span className={styles.diffLinePrefix}>
                      {line.type === 'insert' ? '+' : line.type === 'delete' ? '-' : ' '}
                    </span>
                    <span className={styles.diffLineContent}>{line.value || ' '}</span>
                  </div>
                ))}
              </div>
            </>
          )}

          {tab === 'diff' && !diffLines && (
            <div className={styles.diffEmpty}>{t('versions.noDiff', 'Select two versions to compare first')}</div>
          )}
        </div>
      </div>
    </div>
  );
}
