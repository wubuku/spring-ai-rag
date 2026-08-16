import { useState, useRef, useEffect, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { searchApi, type SearchResult } from '../api/search';
import { filesApi } from '../api/files';
import { CollectionScopeSelector } from '../components/CollectionScopeSelector';
import { SearchResults } from '../components/SearchResults';
import { useToast } from '../components/Toast';
import { useSearchHistory } from '../hooks/useSearchHistory';
import type { CollectionScopeMode } from '../types/api';
import styles from './Search.module.css';

export function Search() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [query, setQuery] = useState<string>('');
  const [useHybrid, setUseHybrid] = useState(true);
  const [scopeMode, setScopeMode] =
    useState<CollectionScopeMode>('CALLER_VISIBLE');
  const [selectedCollectionKeys, setSelectedCollectionKeys] =
    useState<string[]>([]);
  const [hasSearched, setHasSearched] = useState(false);
  const { history, addQuery, removeItem, clearHistory, showHistory, setShowHistory } = useSearchHistory();
  const historyRef = useRef<HTMLDivElement>(null);

  const sortedCollectionKeys = [...selectedCollectionKeys].sort();
  const selectedScopeIsValid =
    scopeMode !== 'SELECTED_COLLECTIONS' || sortedCollectionKeys.length > 0;

  const { data, isPending, refetch } = useQuery({
    queryKey: ['search', query, useHybrid, scopeMode, sortedCollectionKeys],
    queryFn: () => searchApi.search({
      query,
      useHybrid,
      collectionScopeMode: scopeMode,
      collectionKeys: scopeMode === 'SELECTED_COLLECTIONS'
        ? sortedCollectionKeys
        : undefined,
    }),
    enabled: false,
  });

  // Close history panel on outside click
  useEffect(() => {
    if (!showHistory) return;
    const handler = (e: MouseEvent) => {
      if (historyRef.current && !historyRef.current.contains(e.target as Node)) {
        setShowHistory(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showHistory, setShowHistory]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim() || !selectedScopeIsValid) return;
    setHasSearched(true);
    addQuery(query, useHybrid);
    refetch();
    setShowHistory(false);
  };

  const handleHistorySelect = (item: { query: string; useHybrid: boolean }) => {
    setQuery(item.query);
    setUseHybrid(item.useHybrid);
    setShowHistory(false);
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
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      showToast(t('search.openOriginalPdfError', { error: message }), 'error');
    }
  }, [showToast, t]);

  return (
    <div>
      <h1 className="page-title">{t('search.title')}</h1>
      <form onSubmit={handleSearch} className={styles.form}>
        <div className={styles.searchWrapper}>
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            onFocus={() => history.length > 0 && setShowHistory(true)}
            placeholder={t('search.placeholder')}
            className={styles.searchInput}
          />
          {history.length > 0 && (
            <div className={styles.historyToggle} ref={historyRef}>
              <button
                type="button"
                className={styles.historyBtn}
                onClick={() => setShowHistory(v => !v)}
                title={t('search.history') || 'History'}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12 6 12 12 16 14"/>
                </svg>
                {history.length}
              </button>
              {showHistory && (
                <div className={styles.historyPanel}>
                  <div className={styles.historyHeader}>
                    <span>{t('search.recentSearches')}</span>
                    <button type="button" onClick={clearHistory} className={styles.clearAll}>
                      {t('search.clearHistory')}
                    </button>
                  </div>
                  <ul className={styles.historyList}>
                    {history.map(item => (
                      <li key={item.timestamp} className={styles.historyItem}>
                        <button
                          type="button"
                          className={styles.historyItemBtn}
                          onClick={() => handleHistorySelect(item)}
                        >
                          <span className={styles.historyQuery}>{item.query}</span>
                          <span className={styles.historyMeta}>
                            {item.useHybrid ? 'Hybrid' : 'Vector'} ·{' '}
                            {new Date(item.timestamp).toLocaleTimeString()}
                          </span>
                        </button>
                        <button
                          type="button"
                          className={styles.historyRemove}
                          onClick={e => { e.stopPropagation(); removeItem(item.timestamp); }}
                          title={t('common.delete')}
                        >
                          ×
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>
        <label className={styles.hybridLabel}>
          <input
            type="checkbox"
            checked={useHybrid}
            onChange={e => setUseHybrid(e.target.checked)}
          />
          Hybrid
        </label>
        <div className={styles.scopeSelector}>
          <CollectionScopeSelector
            idPrefix="search"
            mode={scopeMode}
            selectedKeys={selectedCollectionKeys}
            onModeChange={setScopeMode}
            onSelectedKeysChange={setSelectedCollectionKeys}
          />
        </div>
        <button
          type="submit"
          disabled={!query.trim() || !selectedScopeIsValid}
          className={styles.searchBtn}
        >
          {t('search.searchButton')}
        </button>
      </form>

      {hasSearched && isPending && <div className={styles.loading}>{t('common.loading')}</div>}

      {data?.data && (
        <SearchResults
          results={data.data.results.map((r: SearchResult) => ({
            documentId: r.documentId ?? 'unknown',
            title: String(r.title || `Document ${r.documentId}`),
            content: String(r.content || r.chunkText || ''),
            score: r.score,
            fulltextScore: r.fulltextScore,
            vectorScore: r.vectorScore,
            source: r.source,
            originalFilename: r.originalFilename,
            fileDirectoryPath: r.fileDirectoryPath,
            indexedFilePath: r.indexedFilePath,
            originalFilePath: r.originalFilePath,
          }))}
          query={data.data.query}
          onViewDirectory={handleViewDirectory}
          onViewIndexedFile={handleViewIndexedFile}
          onOpenOriginalFile={handleOpenOriginalFile}
        />
      )}
    </div>
  );
}
