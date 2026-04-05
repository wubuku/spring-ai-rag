import styles from './SearchResults.module.css';

export interface SearchResultItem {
  documentId: number | string;
  title: string;
  content: string;
  score: number;
}

interface SearchResultsProps {
  results: SearchResultItem[];
  query: string;
}

export function SearchResults({ results, query }: SearchResultsProps) {
  if (results.length === 0) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyIcon}>🔍</span>
        <p>No results found for "{query}"</p>
        <p className={styles.emptyHint}>Try different keywords or adjust your search.</p>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <div className={styles.count}>
        {results.length} result{results.length !== 1 ? 's' : ''} for "{query}"
      </div>
      {results.map((result, index) => (
        <div key={`${result.documentId}-${index}`} className={styles.result}>
          <div className={styles.header}>
            <span className={styles.title}>{result.title}</span>
            <span className={styles.score}>{(result.score * 100).toFixed(1)}%</span>
          </div>
          <p className={styles.snippet}>{result.content}</p>
        </div>
      ))}
    </div>
  );
}
