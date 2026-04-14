package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRerankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Result reranking service
 *
 * <p>Multi-dimensional relevance scoring (keyword matching, position weighting) and diversity scoring,
 * to resort retrieval results and improve final recall quality.
 *
 * <p>This service has no external dependencies and can be migrated directly.
 */
@Service
public class ReRankingService {

    private static final Logger log = LoggerFactory.getLogger(ReRankingService.class);

    private final RagRerankProperties config;

    public ReRankingService(RagProperties ragProperties) {
        this.config = ragProperties.getRerank();
    }

    /**
     * Rerank retrieval results
     *
     * @param query original query
     * @param results initial retrieval results
     * @param maxResults max results
     * @return reranked result list (sorted by final score descending)
     */
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> results, int maxResults) {
        if (!config.isEnabled() || results == null || results.isEmpty()) {
            return results;
        }

        // Calculate dimension scores and merge
        List<RetrievalResult> scored = results.stream()
                .map(r -> {
                    float relevance = calculateRelevanceScore(query, r.getChunkText());
                    float diversity = calculateDiversityScore(r.getChunkText(), results);
                    // Guard against NaN scores from upstream (e.g., fusion division by zero)
                    float rawScore = (float) r.getScore();
                    float safeScore = Float.isNaN(rawScore) ? 0f : rawScore;
                    float finalScore = safeScore * (1 - config.getDiversityWeight())
                            + relevance * config.getDiversityWeight() * 0.5f
                            + diversity * config.getDiversityWeight() * 0.5f;

                    RetrievalResult out = new RetrievalResult();
                    out.setDocumentId(r.getDocumentId());
                    out.setTitle(r.getTitle());
                    out.setChunkText(r.getChunkText());
                    out.setScore(finalScore);
                    out.setVectorScore(r.getVectorScore());
                    out.setFulltextScore(r.getFulltextScore());
                    out.setChunkIndex(r.getChunkIndex());
                    out.setMetadata(r.getMetadata());
                    return out;
                })
                .sorted(Comparator.comparingDouble(RetrievalResult::getScore).reversed())
                .limit(maxResults)
                .toList();

        log.debug("Reranked {} results (enabled={})", scored.size(), config.isEnabled());
        return scored;
    }

    /**
     * Calculate query-text relevance score (keyword matching + position weighting)
     */
    float calculateRelevanceScore(String query, String text) {
        if (query == null || text == null) {
            return 0f;
        }

        String[] queryTerms = query.toLowerCase().split("\\s+");
        String lowerText = text.toLowerCase();

        int matchCount = 0;
        int positionScore = 0;

        for (String term : queryTerms) {
            if (lowerText.contains(term)) {
                matchCount++;
                int pos = lowerText.indexOf(term);
                if (pos < 50) {
                    positionScore += (50 - pos) / 10;
                }
            }
        }

        float termMatchScore = (float) matchCount / queryTerms.length;
        float positionBonus = Math.min(positionScore / 10f, 0.3f);

        return Math.min(termMatchScore + positionBonus, 1.0f);
    }

    /**
     * Calculate diversity score (difference from most similar result)
     */
    float calculateDiversityScore(String text, List<RetrievalResult> allResults) {
        if (allResults.size() <= 1) {
            return 1.0f;
        }

        float maxSimilarity = 0f;
        for (RetrievalResult other : allResults) {
            if (!other.getChunkText().equals(text)) {
                float similarity = calculateTextSimilarity(text, other.getChunkText());
                maxSimilarity = Math.max(maxSimilarity, similarity);
            }
        }

        return 1.0f - maxSimilarity;
    }

    /**
     * Calculate similarity between two texts (Jaccard similarity based on keyword overlap)
     */
    float calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0f;
        }

        Set<String> words1 = new HashSet<>(Arrays.asList(text1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.toLowerCase().split("\\s+")));

        words1.removeIf(w -> w.length() < 2);
        words2.removeIf(w -> w.length() < 2);

        if (words1.isEmpty() || words2.isEmpty()) {
            return 0f;
        }

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return (float) intersection.size() / union.size();
    }
}
