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
import java.util.Locale;
import java.util.Set;

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
    private static final Set<String> NO_OP_PROVIDER_NAMES =
            Set.of("none", "noop", "off");

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
        int finalLimit = maxResults > 0 ? maxResults
                : (config.getTopN() > 0 ? config.getTopN() : results.size());
        boolean selectorActive = isDocumentDiversificationActive(
                results.size(), finalLimit);
        List<RetrievalResult> providerCandidates = results;
        int rankingDepth = finalLimit;
        if (selectorActive) {
            int providerCandidateCount = Math.min(
                    results.size(), config.getCandidateLimit());
            if (providerCandidateCount < results.size()) {
                providerCandidates = new ArrayList<>(
                        results.subList(0, providerCandidateCount));
            }
            rankingDepth = providerCandidateCount;
        }

        List<RetrievalResult> out =
                provider.rerank(query, providerCandidates, rankingDepth);
        if (out == null) {
            throw new IllegalStateException(
                    "Rerank provider '" + provider.getName() + "' returned null");
        }
        if (out.size() > rankingDepth) {
            log.warn("Rerank provider {} returned {} results for ranking depth {}; truncating",
                    provider.getName(), out.size(), rankingDepth);
            out = limitResults(out, rankingDepth);
        }
        if (selectorActive) {
            out = RerankResultSelector.select(
                    out,
                    finalLimit,
                    config.getPreferredMaxChunksPerDocument());
        } else if (out.size() > finalLimit) {
            out = limitResults(out, finalLimit);
        }
        log.debug("Reranked {} → {} results (provider={}, enabled={})",
                results.size(), out.size(), provider.getName(), config.isEnabled());
        return out;
    }

    private boolean isDocumentDiversificationActive(
            int candidateCount,
            int finalLimit) {
        int preferredMax = config.getPreferredMaxChunksPerDocument();
        if (finalLimit <= 0
                || preferredMax <= 0
                || preferredMax >= finalLimit
                || config.getCandidateLimit() <= finalLimit
                || candidateCount <= finalLimit) {
            return false;
        }
        String providerName = provider.getName();
        String normalized = providerName == null
                ? ""
                : providerName.trim().toLowerCase(Locale.ROOT);
        return !NO_OP_PROVIDER_NAMES.contains(normalized);
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
