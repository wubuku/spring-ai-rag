import { useState, useRef, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { searchApi } from '../api/search';
import { SearchResults } from '../components/SearchResults';
import { useSearchHistory } from '../hooks/useSearchHistory';
import styles from './Search.module.css';

export function Search() {
  const { t } = useTranslation();
  const [query, setQuery] = useState<string>('');
  const [useHybrid, setUseHybrid] = useState(true);
  const { history, addQuery, removeItem, clearHistory, showHistory, setShowHistory } = useSearchHistory();
  const historyRef = useRef<HTMLDivElement>(null);

  const { data, isPending, refetch } = useQuery({
    queryKey: ['search', query, useHybrid],
    queryFn: () => searchApi.search({ query, useHybrid }),
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
    if (!query.trim()) return;
    addQuery(query, useHybrid);
    refetch();
    setShowHistory(false);
  };

  const handleHistorySelect = (item: { query: string; useHybrid: boolean }) => {
    setQuery(item.query);
    setUseHybrid(item.useHybrid);
    setShowHistory(false);
  };

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
        <button type="submit" disabled={!query.trim()} className={styles.searchBtn}>
          {t('search.searchButton')}
        </button>
      </form>

      {isPending && <div className={styles.loading}>{t('common.loading')}</div>}

      {data?.data && (
        <SearchResults
          results={data.data.results.map((r: {documentId?:string|number; title?:string; content?:string; chunkText?:string; score?:string|number; fulltextScore?:number; vectorScore?:number}) => ({
            documentId: r.documentId ?? 'unknown',
            title: String(r.title || `Document ${r.documentId}`),
            content: String(r.content || r.chunkText || ''),
            score: (typeof r.score === 'number' ? r.score : r.fulltextScore) ?? 0,
            fulltextScore: r.fulltextScore,
            vectorScore: r.vectorScore,
          }))}
          query={data.data.query}
        />
      )}
    </div>
  );
}
