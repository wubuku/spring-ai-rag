package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 结果重排序服务
 *
 * <p>基于多维度相关性（关键词匹配、位置权重）和多样性评分，
 * 对检索结果进行二次排序，提升最终召回质量。
 *
 * <p>此服务无外部依赖，可直接迁移使用。
 */
@Service
public class ReRankingService {

    private static final Logger log = LoggerFactory.getLogger(ReRankingService.class);

    @Value("${rag.rerank.enabled:false}")
    private boolean enabled;

    @Value("${rag.rerank.diversity-weight:0.2}")
    private float diversityWeight;

    /**
     * 对检索结果进行重排序
     *
     * @param query 原始查询
     * @param results 初始检索结果
     * @param maxResults 返回结果数量
     * @return 重排序后的结果列表（按最终得分降序）
     */
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> results, int maxResults) {
        if (!enabled || results == null || results.isEmpty()) {
            return results;
        }

        // 计算各维度得分并合并
        List<RetrievalResult> scored = results.stream()
                .map(r -> {
                    float relevance = calculateRelevanceScore(query, r.getChunkText());
                    float diversity = calculateDiversityScore(r.getChunkText(), results);
                    float finalScore = (float) r.getScore() * (1 - diversityWeight)
                            + relevance * diversityWeight * 0.5f
                            + diversity * diversityWeight * 0.5f;

                    RetrievalResult out = new RetrievalResult();
                    out.setDocumentId(r.getDocumentId());
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
                .collect(Collectors.toList());

        log.debug("Reranked {} results (enabled={})", scored.size(), enabled);
        return scored;
    }

    /**
     * 计算查询与文本的相关性得分（关键词匹配 + 位置权重）
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
     * 计算多样性得分（与最相似结果的差异化程度）
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
     * 计算两段文本的相似度（Jaccard 相似度，基于关键词重叠）
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
