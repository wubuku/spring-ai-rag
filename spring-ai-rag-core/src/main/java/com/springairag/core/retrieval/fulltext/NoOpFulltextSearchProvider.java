package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;

import java.util.Collections;
import java.util.List;

/**
 * No-op full-text search strategy
 *
 * <p>Fallback when neither pg_trgm nor pg_jieba is available.
 * All searches return empty list; system degrades to pure vector search.
 */
public class NoOpFulltextSearchProvider implements FulltextSearchProvider {

    @Override
    public String getName() {
        return "none";
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available (returns empty results)
    }

    @Override
    public List<RetrievalResult> search(String query, List<Long> documentIds,
                                        List<Long> excludeIds, int limit, double minScore) {
        return Collections.emptyList();
    }
}
