package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;

import java.util.Collections;
import java.util.List;

/**
 * Full-text search provider interface
 *
 * <p>Abstracts different full-text search backends (pg_trgm, pg_jieba, etc.),
 * automatically selected by {@link FulltextSearchProviderFactory} based on database extension availability.
 *
 * <p>Implementation requirements:
 * <ul>
 *   <li>Should return empty list when unavailable, not throw exceptions</li>
 *   <li>Results set relevance score via {@link RetrievalResult#setFulltextScore(double)}</li>
 * </ul>
 */
public interface FulltextSearchProvider {

    /**
     * Provider name (used for logging and configuration)
     */
    String getName();

    /**
     * Whether available (detected at startup, result cached)
     */
    boolean isAvailable();

    /**
     * Execute full-text search
     *
     * @param query        query text
     * @param documentIds  document ID filter (null means search all)
     * @param excludeIds   embedding IDs to exclude
     * @param limit        max results to return
     * @param minScore     provider-specific minimum score threshold; boolean
     *                     lexeme FTS providers may rely on the database match
     *                     predicate instead because ts_rank is not comparable
     *                     to vector similarity
     * @param embeddingProfileId active embedding profile ID
     * @return retrieval results list (sorted by relevance descending)
     */
    List<RetrievalResult> search(String query, List<Long> documentIds,
                                 List<Long> excludeIds, int limit, double minScore,
                                 long embeddingProfileId);

    /**
     * 使用统一检索范围执行全文检索。
     *
     * <p>旧 provider 只有在 scope 不含 Collection 或 document type 条件时才会收到
     * 兼容调用；无法表达的新范围会 fail closed。
     */
    default List<RetrievalResult> searchInScope(
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            double minScore,
            long embeddingProfileId) {
        RetrievalScope effective = scope != null
                ? scope
                : RetrievalScope.unscoped();
        if (effective.matchNone()
                || effective.collectionFilter()
                != RetrievalScope.CollectionFilter.NONE
                || effective.documentType() != null) {
            return Collections.emptyList();
        }
        return search(
                query,
                effective.documentIds().isEmpty()
                        ? null
                        : effective.documentIds(),
                excludeIds,
                limit,
                minScore,
                embeddingProfileId);
    }

    /**
     * 使用统一检索范围和可选 JSONB containment 条件执行全文检索。
     *
     * <p>旧扩展 provider 无法表达 payload filter 时必须 fail closed。</p>
     */
    default List<RetrievalResult> searchInScope(
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            double minScore,
            long embeddingProfileId,
            JsonbContainmentFilter payloadFilter) {
        if (payloadFilter != null) {
            return Collections.emptyList();
        }
        return searchInScope(
                query, scope, excludeIds, limit, minScore,
                embeddingProfileId);
    }

    /**
     * 使用统一范围和已校验的 metadata/payload containment。
     */
    default List<RetrievalResult> searchInScope(
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            double minScore,
            long embeddingProfileId,
            RetrievalFilters filters) {
        if (filters == null || filters.isEmpty()) {
            return searchInScope(
                    query, scope, excludeIds, limit, minScore,
                    embeddingProfileId, (JsonbContainmentFilter) null);
        }
        if (filters.metadataContains() == null
                && filters.payloadContainsAll().size() == 1) {
            return searchInScope(
                    query, scope, excludeIds, limit, minScore,
                    embeddingProfileId, filters.payloadContainsAll().getFirst());
        }
        return Collections.emptyList();
    }

    /**
     * 供混合检索诊断使用的详细结果。公开 List API 继续 fail-open。
     */
    default SearchResult searchInScopeDetailed(
            String query,
            RetrievalScope scope,
            List<Long> excludeIds,
            int limit,
            double minScore,
            long embeddingProfileId,
            RetrievalFilters filters) {
        try {
            return SearchResult.success(searchInScope(
                    query, scope, excludeIds, limit, minScore,
                    embeddingProfileId, filters));
        } catch (RuntimeException e) {
            return SearchResult.failure(e.getClass().getSimpleName());
        }
    }

    record SearchResult(
            List<RetrievalResult> results,
            String errorCode,
            int candidateCount) {

        public SearchResult {
            results = results == null ? List.of() : List.copyOf(results);
            candidateCount = Math.max(candidateCount, results.size());
        }

        public SearchResult(
                List<RetrievalResult> results,
                String errorCode) {
            this(
                    results,
                    errorCode,
                    results == null ? 0 : results.size());
        }

        public static SearchResult success(List<RetrievalResult> results) {
            return new SearchResult(
                    results,
                    null,
                    results == null ? 0 : results.size());
        }

        public static SearchResult success(
                List<RetrievalResult> results,
                int candidateCount) {
            return new SearchResult(results, null, candidateCount);
        }

        public static SearchResult failure(String errorCode) {
            String code = errorCode == null || errorCode.isBlank()
                    ? "ERROR"
                    : errorCode;
            return new SearchResult(List.of(), code, 0);
        }

        public boolean failed() {
            return errorCode != null;
        }
    }
}
