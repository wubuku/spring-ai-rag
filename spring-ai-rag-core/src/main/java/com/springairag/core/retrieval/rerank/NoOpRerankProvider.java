package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;

import java.util.List;

/**
 * No-op rerank: returns results unchanged (truncated to ranking depth).
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
    public List<RetrievalResult> rerank(
            String query,
            List<RetrievalResult> results,
            int rankingDepth) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        if (rankingDepth > 0 && results.size() > rankingDepth) {
            return results.subList(0, rankingDepth);
        }
        return results;
    }
}
