import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { collectionsApi } from '../../api/collections';
import type { CollectionScopeMode } from '../../types/api';
import styles from './CollectionScopeSelector.module.css';

const PAGE_SIZE = 50;
const MAX_SELECTED_COLLECTIONS = 100;

const MODES: CollectionScopeMode[] = [
  'CALLER_VISIBLE',
  'ANY_COLLECTION',
  'SELECTED_COLLECTIONS',
];

const MODE_LABEL_KEYS: Record<CollectionScopeMode, string> = {
  CALLER_VISIBLE: 'collectionScope.callerVisible',
  ANY_COLLECTION: 'collectionScope.anyCollection',
  SELECTED_COLLECTIONS: 'collectionScope.selectedCollections',
};

interface CollectionScopeSelectorProps {
  idPrefix: string;
  mode: CollectionScopeMode;
  selectedKeys: string[];
  onModeChange: (mode: CollectionScopeMode) => void;
  onSelectedKeysChange: (keys: string[]) => void;
  disabled?: boolean;
}

export function CollectionScopeSelector({
  idPrefix,
  mode,
  selectedKeys,
  onModeChange,
  onSelectedKeysChange,
  disabled = false,
}: CollectionScopeSelectorProps) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedQuery(query.trim());
      setPage(0);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const collectionsQuery = useQuery({
    queryKey: ['collection-scope-options', page, debouncedQuery],
    queryFn: () => collectionsApi.list({
      page,
      size: PAGE_SIZE,
      query: debouncedQuery || undefined,
    }),
    enabled: mode === 'SELECTED_COLLECTIONS',
  });

  const collections = collectionsQuery.data?.data.collections ?? [];
  const total = collectionsQuery.data?.data.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const selectedSet = useMemo(() => new Set(selectedKeys), [selectedKeys]);
  const selectionLimitReached = selectedKeys.length >= MAX_SELECTED_COLLECTIONS;

  const toggleCollection = (collectionKey: string) => {
    if (disabled) return;
    if (selectedSet.has(collectionKey)) {
      onSelectedKeysChange(selectedKeys.filter(key => key !== collectionKey));
      return;
    }
    if (!selectionLimitReached) {
      onSelectedKeysChange([...selectedKeys, collectionKey]);
    }
  };

  return (
    <fieldset className={styles.fieldset} disabled={disabled}>
      <legend className={styles.legend}>{t('collectionScope.label')}</legend>
      <div className={styles.modeControl}>
        {MODES.map(scopeMode => (
          <label
            key={scopeMode}
            className={`${styles.modeOption} ${
              mode === scopeMode ? styles.modeOptionActive : ''
            }`}
          >
            <input
              type="radio"
              name={`${idPrefix}-collection-scope-mode`}
              value={scopeMode}
              checked={mode === scopeMode}
              readOnly
              onClick={() => onModeChange(scopeMode)}
              data-testid={`${idPrefix}-scope-${scopeMode}`}
            />
            <span>{t(MODE_LABEL_KEYS[scopeMode])}</span>
          </label>
        ))}
      </div>

      {mode === 'SELECTED_COLLECTIONS' && (
        <div className={styles.selector}>
          <div className={styles.selectorHeader}>
            <label htmlFor={`${idPrefix}-collection-query`} className={styles.searchLabel}>
              {t('collectionScope.searchLabel')}
            </label>
            <span className={styles.selectedCount} data-testid={`${idPrefix}-selected-count`}>
              {t('collectionScope.selectedCount', {
                count: selectedKeys.length,
                max: MAX_SELECTED_COLLECTIONS,
              })}
            </span>
          </div>
          <input
            id={`${idPrefix}-collection-query`}
            type="search"
            value={query}
            onChange={event => setQuery(event.target.value)}
            placeholder={t('collectionScope.searchPlaceholder')}
            className={styles.searchInput}
            data-testid={`${idPrefix}-collection-query`}
          />

          {selectedKeys.length === 0 && (
            <div className={styles.validation} role="alert">
              {t('collectionScope.selectionRequired')}
            </div>
          )}
          {selectionLimitReached && (
            <div className={styles.limitMessage}>
              {t('collectionScope.selectionLimit', {
                max: MAX_SELECTED_COLLECTIONS,
              })}
            </div>
          )}

          <div className={styles.options} data-testid={`${idPrefix}-collection-options`}>
            {collectionsQuery.isPending && (
              <div className={styles.status}>{t('common.loading')}</div>
            )}
            {collectionsQuery.isError && (
              <div className={styles.error} role="alert">
                {t('collectionScope.loadError')}
              </div>
            )}
            {!collectionsQuery.isPending
              && !collectionsQuery.isError
              && collections.length === 0 && (
                <div className={styles.status}>{t('collectionScope.empty')}</div>
              )}
            {collections.map(collection => {
              const selected = selectedSet.has(collection.collectionKey);
              return (
                <label key={collection.collectionKey} className={styles.collectionOption}>
                  <input
                    type="checkbox"
                    checked={selected}
                    onChange={() => toggleCollection(collection.collectionKey)}
                    disabled={disabled || (!selected && selectionLimitReached)}
                    value={collection.collectionKey}
                  />
                  <span className={styles.collectionIdentity}>
                    <strong>{collection.name}</strong>
                    <code title={collection.collectionKey}>{collection.collectionKey}</code>
                  </span>
                  <span className={styles.documentCount}>
                    {t('collectionScope.documentCount', {
                      count: collection.documentCount ?? 0,
                    })}
                  </span>
                </label>
              );
            })}
          </div>

          <div className={styles.pagination}>
            <button
              type="button"
              onClick={() => setPage(current => Math.max(0, current - 1))}
              disabled={disabled || page === 0 || collectionsQuery.isPending}
            >
              {t('collectionScope.previous')}
            </button>
            <span>
              {t('collectionScope.page', {
                page: page + 1,
                total: totalPages,
              })}
            </span>
            <button
              type="button"
              onClick={() => setPage(current => current + 1)}
              disabled={
                disabled
                || page + 1 >= totalPages
                || collectionsQuery.isPending
              }
            >
              {t('collectionScope.next')}
            </button>
          </div>
        </div>
      )}
    </fieldset>
  );
}
