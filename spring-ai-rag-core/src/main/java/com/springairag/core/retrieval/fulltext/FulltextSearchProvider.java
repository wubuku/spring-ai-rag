package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.JsonbContainmentFilter;
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
}
