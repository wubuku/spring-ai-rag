package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.retrieval.rerank.HeuristicRerankProvider;
import com.springairag.core.retrieval.rerank.RerankProvider;
import com.springairag.core.retrieval.rerank.RerankProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Result re-ranking facade.
 *
 * <p>Delegates to a {@link RerankProvider} selected by {@code rag.rerank.provider}
 * (heuristic / http / none). Keeps package-private helpers for unit tests of the
 * heuristic algorithm via {@link HeuristicRerankProvider}.
 */
@Service
public class ReRankingService {

    private static final Logger log = LoggerFactory.getLogger(ReRankingService.class);

    private final RagRerankProperties config;
    private final RerankProvider provider;
    private final HeuristicRerankProvider heuristicForTests;

    @Autowired
    public ReRankingService(RagProperties ragProperties, RerankProviderFactory factory) {
        this.config = ragProperties.getRerank();
        this.provider = factory.create();
        this.heuristicForTests = new HeuristicRerankProvider(this.config);
        log.info("ReRankingService ready: enabled={}, provider={}",
                config.isEnabled(), provider.getName());
    }

    /** Test constructor with explicit provider (not for Spring). */
    public ReRankingService(RagRerankProperties config, RerankProvider provider) {
        this.config = config != null ? config : new RagRerankProperties();
        this.provider = provider != null ? provider : new HeuristicRerankProvider(this.config);
        this.heuristicForTests = new HeuristicRerankProvider(this.config);
    }

    /**
     * Rerank retrieval results.
     */
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> results, int maxResults) {
        if (!config.isEnabled() || results == null || results.isEmpty()) {
            return results;
        }
        int limit = maxResults > 0 ? maxResults
                : (config.getTopN() > 0 ? config.getTopN() : results.size());
        List<RetrievalResult> out = provider.rerank(query, results, limit);
        if (out == null) {
            throw new IllegalStateException(
                    "Rerank provider '" + provider.getName() + "' returned null");
        }
        if (out.size() > limit) {
            log.warn("Rerank provider {} returned {} results for limit {}; truncating",
                    provider.getName(), out.size(), limit);
            out = limitResults(out, limit);
        }
        log.debug("Reranked {} → {} results (provider={}, enabled={})",
                results.size(), out.size(), provider.getName(), config.isEnabled());
        return out;
    }

    /**
     * Applies the final caller-visible result bound to provider or fallback output.
     */
    public static List<RetrievalResult> limitResults(
            List<RetrievalResult> results,
            int maxResults) {
        if (results == null || maxResults <= 0 || results.size() <= maxResults) {
            return results;
        }
        return new ArrayList<>(results.subList(0, maxResults));
    }

    // --- package-private helpers retained for ReRankingServiceTest ---

    float calculateRelevanceScore(String query, String text) {
        return heuristicForTests.calculateRelevanceScore(query, text);
    }

    float calculateDiversityScore(String text, List<RetrievalResult> allResults) {
        return heuristicForTests.calculateDiversityScore(text, allResults);
    }

    float calculateTextSimilarity(String text1, String text2) {
        return heuristicForTests.calculateTextSimilarity(text1, text2);
    }
}
