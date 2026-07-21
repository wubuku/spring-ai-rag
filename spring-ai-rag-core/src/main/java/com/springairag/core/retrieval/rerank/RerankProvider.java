package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;

import java.util.List;

/**
 * SPI for re-ranking retrieval results (mirrors FulltextSearchProvider style).
 */
public interface RerankProvider {

    String getName();

    boolean isAvailable();

    /**
     * @param query      user query
     * @param results    candidate hits
     * @param maxResults limit after rerank
     * @return reranked list (must not be null; may return input unchanged)
     */
    List<RetrievalResult> rerank(String query, List<RetrievalResult> results, int maxResults);
}
