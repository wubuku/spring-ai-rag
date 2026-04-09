package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;

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
     * @param minScore     minimum relevance score threshold
     * @return retrieval results list (sorted by relevance descending)
     */
    List<RetrievalResult> search(String query, List<Long> documentIds,
                                 List<Long> excludeIds, int limit, double minScore);
}
