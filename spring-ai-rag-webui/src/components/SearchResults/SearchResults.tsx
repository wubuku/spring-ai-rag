import { useTranslation } from 'react-i18next';
import styles from './SearchResults.module.css';

export interface SearchResultItem {
  documentId: number | string;
  title?: string;
  content?: string;
  chunkText?: string;
  score?: number | string;
  fulltextScore?: number;
  vectorScore?: number;
  source?: string;
  originalFilename?: string;
  fileDirectoryPath?: string;
  indexedFilePath?: string;
  originalFilePath?: string;
  [key: string]: unknown;
}

interface SearchResultsProps {
  results: SearchResultItem[];
  query: string;
  onViewDirectory?: (path: string) => void;
  onViewIndexedFile?: (directoryPath: string, filePath: string) => void;
  onOpenOriginalFile?: (path: string) => void;
}

type MatchChannel = 'hybrid' | 'semantic' | 'keyword';

function hasPositiveScore(value: number | undefined): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function resolveMatchChannel(
  result: SearchResultItem,
): MatchChannel | undefined {
  const hasVectorMatch = hasPositiveScore(result.vectorScore);
  const hasFulltextMatch = hasPositiveScore(result.fulltextScore);

  if (hasVectorMatch && hasFulltextMatch) return 'hybrid';
  if (hasVectorMatch) return 'semantic';
  if (hasFulltextMatch) return 'keyword';
  return undefined;
}

export function SearchResults({
  results,
  query,
  onViewDirectory,
  onViewIndexedFile,
  onOpenOriginalFile,
}: SearchResultsProps) {
  const { t } = useTranslation();

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
      {results.map((result, index) => {
        const rank = index + 1;
        const matchChannel = resolveMatchChannel(result);
        const channelLabel = matchChannel
          ? t(`search.matchChannel.${matchChannel}`)
          : undefined;
        const indicatorTitle = channelLabel
          ? t('search.resultIndicatorTitle', { rank, channel: channelLabel })
          : undefined;
        const hasFileActions = Boolean(
          result.fileDirectoryPath
          || result.indexedFilePath
          || result.originalFilePath,
        );

        return (
          <div key={`${result.documentId}-${index}`} className={styles.result}>
            <div className={styles.header}>
              <span className={styles.title}>{result.title || `Document ${result.documentId}`}</span>
              {channelLabel && (
                <span className={styles.indicator} title={indicatorTitle}>
                  {channelLabel}
                </span>
              )}
            </div>
            <p className={styles.snippet}>{result.content || result.chunkText || ''}</p>
            {hasFileActions && (
              <div className={styles.provenance}>
                <span className={styles.sourceFilename}>
                  {result.originalFilename || t('search.originalPdfFallback')}
                </span>
                <div className={styles.sourceActions}>
                  {result.fileDirectoryPath && onViewDirectory && (
                    <button
                      type="button"
                      className={styles.sourceAction}
                      onClick={() => onViewDirectory(result.fileDirectoryPath!)}
                    >
                      {t('search.viewFileDirectory')}
                    </button>
                  )}
                  {result.fileDirectoryPath
                    && result.indexedFilePath
                    && onViewIndexedFile && (
                    <button
                      type="button"
                      className={styles.sourceAction}
                      onClick={() => onViewIndexedFile(
                        result.fileDirectoryPath!,
                        result.indexedFilePath!,
                      )}
                    >
                      {t('search.viewIndexedFile')}
                    </button>
                  )}
                  {result.originalFilePath && onOpenOriginalFile && (
                    <button
                      type="button"
                      className={styles.sourceAction}
                      onClick={() => onOpenOriginalFile(result.originalFilePath!)}
                    >
                      {t('search.openOriginalPdf')}
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
