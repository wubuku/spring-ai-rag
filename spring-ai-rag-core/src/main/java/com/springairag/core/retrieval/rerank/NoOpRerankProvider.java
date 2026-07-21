package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;

import java.util.List;

/**
 * No-op rerank: returns results unchanged (truncated to maxResults).
 */
public class NoOpRerankProvider implements RerankProvider {

    @Override
    public String getName() {
        return "none";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> results, int maxResults) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        if (maxResults > 0 && results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }
}
