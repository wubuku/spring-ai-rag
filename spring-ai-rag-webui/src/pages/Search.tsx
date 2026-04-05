import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchApi } from '../api/search';
import styles from './Search.module.css';

export function Search() {
  const [query, setQuery] = useState('');
  const [useHybrid, setUseHybrid] = useState(true);

  const { data, isPending, refetch } = useQuery({
    queryKey: ['search', query, useHybrid],
    queryFn: () => searchApi.search({ query, useHybrid }),
    enabled: false,
  });

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;
    refetch();
  };

  return (
    <div>
      <h1 className="page-title">Search</h1>
      <form onSubmit={handleSearch} className={styles.form}>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Enter your search query..."
          className={styles.searchInput}
        />
        <label className={styles.hybridLabel}>
          <input
            type="checkbox"
            checked={useHybrid}
            onChange={(e) => setUseHybrid(e.target.checked)}
          />
          Hybrid
        </label>
        <button type="submit" disabled={!query.trim()} className={styles.searchBtn}>
          Search
        </button>
      </form>

      {isPending && <div>Searching...</div>}

      {data?.data && (
        <div className={styles.results}>
          <div className={styles.resultCount}>{data.data.total} results for "{data.data.query}"</div>
          {data.data.results.map((r, i) => (
            <div key={i} className={styles.result}>
              <div className={styles.resultHeader}>
                <span className={styles.title}>{r.title}</span>
                <span className={styles.score}>{(r.score * 100).toFixed(1)}%</span>
              </div>
              <div className={styles.snippet}>{r.content.slice(0, 200)}...</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
