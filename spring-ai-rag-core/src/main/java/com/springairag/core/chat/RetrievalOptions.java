package com.springairag.core.chat;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagRetrievalProperties;

/**
 * Immutable effective retrieval options for one chat attempt.
 */
public record RetrievalOptions(
        int maxResults,
        double minScore,
        boolean useHybridSearch,
        boolean useRerank,
        double vectorWeight,
        double fulltextWeight) {

    public RetrievalOptions {
        if (maxResults < 1 || maxResults > 100) {
            throw new IllegalArgumentException("maxResults must be between 1 and 100");
        }
        if (minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
        if (vectorWeight < 0 || vectorWeight > 1
                || fulltextWeight < 0 || fulltextWeight > 1) {
            throw new IllegalArgumentException("retrieval weights must be between 0 and 1");
        }
    }

    public RetrievalConfig toConfig() {
        return RetrievalConfig.builder()
                .maxResults(maxResults)
                .minScore(minScore)
                .useHybridSearch(useHybridSearch)
                .useRerank(useRerank)
                .vectorWeight(vectorWeight)
                .fulltextWeight(fulltextWeight)
                .build();
    }

    public static RetrievalOptions from(
            RagRetrievalProperties global,
            RetrievalConfig domain,
            boolean maxResultsExplicit,
            int requestMaxResults,
            boolean hybridExplicit,
            boolean requestHybrid,
            boolean rerankExplicit,
            boolean requestRerank) {
        int max = maxResultsExplicit
                ? requestMaxResults
                : (domain != null ? domain.getMaxResults() : 5);
        boolean hybrid = hybridExplicit
                ? requestHybrid
                : (domain != null ? domain.isUseHybridSearch() : true);
        boolean rerank = rerankExplicit
                ? requestRerank
                : (domain != null ? domain.isUseRerank() : true);
        double min = domain != null ? domain.getMinScore() : global.getMinScore();
        double vector = domain != null ? domain.getVectorWeight() : global.getVectorWeight();
        double fulltext = domain != null ? domain.getFulltextWeight() : global.getFulltextWeight();
        return new RetrievalOptions(
                Math.min(Math.max(max, 1), 100),
                min,
                hybrid,
                rerank,
                vector,
                fulltext);
    }
}
