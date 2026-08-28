import { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { searchApi, type SearchResult } from '../api/search';
import { filesApi } from '../api/files';
import { CollectionScopeSelector } from '../components/CollectionScopeSelector';
import { SearchResults } from '../components/SearchResults';
import { useToast } from '../components/Toast';
import { useSearchHistory } from '../hooks/useSearchHistory';
import type { CollectionScopeMode } from '../types/api';
import {
  readWorkspaceState,
  removeWorkspaceState,
  writeWorkspaceState,
} from '../utils/workspaceState';
import styles from './Search.module.css';

interface SearchUrlState {
  query: string;
  useHybrid: boolean;
  scopeMode: CollectionScopeMode;
  selectedCollectionKeys: string[];
}

interface SearchDraft extends SearchUrlState {
  baseSubmittedSearch: string;
}

function isSearchDraft(value: unknown): value is SearchDraft {
  if (!value || typeof value !== 'object') return false;
  const draft = value as Partial<SearchDraft>;
  return typeof draft.query === 'string'
    && typeof draft.useHybrid === 'boolean'
    && typeof draft.scopeMode === 'string'
    && Array.isArray(draft.selectedCollectionKeys)
    && draft.selectedCollectionKeys.every(key => typeof key === 'string')
    && typeof draft.baseSubmittedSearch === 'string';
}

function readSearchUrlState(search: string): SearchUrlState {
  const params = new URLSearchParams(search);
  const scopeModeParam = params.get('scopeMode');
  const scopeMode: CollectionScopeMode =
    scopeModeParam === 'ANY_COLLECTION' || scopeModeParam === 'SELECTED_COLLECTIONS'
      ? scopeModeParam
      : 'CALLER_VISIBLE';

  return {
    query: params.get('query')?.trim() ?? '',
    useHybrid: params.get('hybrid') !== 'false',
    scopeMode,
    selectedCollectionKeys: params.getAll('collectionKey').sort(),
  };
}

export function Search() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const urlState = useMemo(
    () => readSearchUrlState(location.search),
    [location.search],
  );
  const initialDraft = useMemo(
    () => readWorkspaceState('search-draft', isSearchDraft),
    [],
  );
  const applicableDraft = initialDraft?.baseSubmittedSearch === location.search
    ? initialDraft
    : null;
  const [query, setQuery] = useState<string>(applicableDraft?.query ?? urlState.query);
  const [useHybrid, setUseHybrid] = useState(
    applicableDraft?.useHybrid ?? urlState.useHybrid,
  );
  const [scopeMode, setScopeMode] = useState<CollectionScopeMode>(
    applicableDraft?.scopeMode ?? urlState.scopeMode,
  );
  const [selectedCollectionKeys, setSelectedCollectionKeys] =
    useState<string[]>(
      applicableDraft?.selectedCollectionKeys ?? urlState.selectedCollectionKeys,
    );
  const { history, addQuery, removeItem, clearHistory, showHistory, setShowHistory } = useSearchHistory();
  const historyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const draft = readWorkspaceState('search-draft', isSearchDraft);
    if (draft?.baseSubmittedSearch === location.search) {
      setQuery(draft.query);
      setUseHybrid(draft.useHybrid);
      setScopeMode(draft.scopeMode);
      setSelectedCollectionKeys(draft.selectedCollectionKeys);
      return;
    }
    removeWorkspaceState('search-draft');
    setQuery(urlState.query);
    setUseHybrid(urlState.useHybrid);
    setScopeMode(urlState.scopeMode);
    setSelectedCollectionKeys(urlState.selectedCollectionKeys);
  }, [location.search, urlState]);

  useEffect(() => {
    const current = {
      query,
      useHybrid,
      scopeMode,
      selectedCollectionKeys: [...selectedCollectionKeys].sort(),
    };
    const clean = current.query === urlState.query
      && current.useHybrid === urlState.useHybrid
      && current.scopeMode === urlState.scopeMode
      && current.selectedCollectionKeys.join('\0')
        === urlState.selectedCollectionKeys.join('\0');
    if (clean) {
      removeWorkspaceState('search-draft');
    } else {
      writeWorkspaceState('search-draft', {
        ...current,
        baseSubmittedSearch: location.search,
      } satisfies SearchDraft);
    }
  }, [
    location.search,
    query,
    scopeMode,
    selectedCollectionKeys,
    urlState,
    useHybrid,
  ]);

  const sortedCollectionKeys = [...selectedCollectionKeys].sort();
  const selectedScopeIsValid =
    scopeMode !== 'SELECTED_COLLECTIONS' || sortedCollectionKeys.length > 0;

  const urlScopeIsValid =
    urlState.scopeMode !== 'SELECTED_COLLECTIONS'
    || urlState.selectedCollectionKeys.length > 0;
  const { data, isPending, refetch } = useQuery({
    queryKey: [
      'search',
      urlState.query,
      urlState.useHybrid,
      urlState.scopeMode,
      urlState.selectedCollectionKeys,
    ],
    queryFn: () => searchApi.search({
      query: urlState.query,
      useHybrid: urlState.useHybrid,
      collectionScopeMode: urlState.scopeMode,
      collectionKeys: urlState.scopeMode === 'SELECTED_COLLECTIONS'
        ? urlState.selectedCollectionKeys
        : undefined,
    }),
    enabled: Boolean(urlState.query) && urlScopeIsValid,
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
    addQuery(query, useHybrid);
    setShowHistory(false);

    const params = new URLSearchParams();
    params.set('query', query.trim());
    params.set('hybrid', String(useHybrid));
    params.set('scopeMode', scopeMode);
    if (scopeMode === 'SELECTED_COLLECTIONS') {
      sortedCollectionKeys.forEach(key => params.append('collectionKey', key));
    }
    const nextSearch = `?${params.toString()}`;
    if (location.search === nextSearch) {
      void refetch();
    } else {
      removeWorkspaceState('search-draft');
      navigate(`/search${nextSearch}`);
    }
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

      {urlState.query && isPending && <div className={styles.loading}>{t('common.loading')}</div>}

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
          query={data.data.query || urlState.query}
          onViewDirectory={handleViewDirectory}
          onViewIndexedFile={handleViewIndexedFile}
          onOpenOriginalFile={handleOpenOriginalFile}
        />
      )}
    </div>
  );
}
