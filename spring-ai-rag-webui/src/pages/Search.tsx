import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchApi } from '../api/search';
import { SearchResults } from '../components/SearchResults';
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
          onChange={e => setQuery(e.target.value)}
          placeholder="Enter your search query..."
          className={styles.searchInput}
        />
        <label className={styles.hybridLabel}>
          <input
            type="checkbox"
            checked={useHybrid}
            onChange={e => setUseHybrid(e.target.checked)}
          />
          Hybrid
        </label>
        <button type="submit" disabled={!query.trim()} className={styles.searchBtn}>
          Search
        </button>
      </form>

      {isPending && <div className={styles.loading}>Searching...</div>}

      {data?.data && (
        <SearchResults results={data.data.results} query={data.data.query} />
      )}
    </div>
  );
}
