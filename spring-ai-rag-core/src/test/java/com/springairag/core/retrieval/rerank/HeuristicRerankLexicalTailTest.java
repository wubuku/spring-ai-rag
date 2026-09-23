package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启发式重排的词法特征长尾（Batch 594，JaCoCo 驱动）：边界感知
 * 匹配的阻塞与放行、位置加分、全标点词的原样保留、词法特征上限
 * 截断、CJK/拉丁混合段切分与多文种判定。
 */
class HeuristicRerankLexicalTailTest {

    private final HeuristicRerankProvider provider =
            new HeuristicRerankProvider(new RagRerankProperties());

    @Test
    void boundaryAwareTermBlockedInsideLongerTokenScoresZero() {
        // "alpha" 只出现在 "xalphax" 内部 → 全部出现被边界阻塞。
        float score = provider.calculateRelevanceScore("alpha", "xalphax beta");
        assertEquals(0f, score);
    }

    @Test
    void blockedFirstOccurrenceStillFindsLaterValidMatch() {
        // 首个出现被阻塞后继续向后搜索并命中独立出现的词。
        float blockedFirst = provider.calculateRelevanceScore(
                "alpha", "xalphax alpha world");
        assertTrue(blockedFirst > 0f);
    }

    @Test
    void earlyPositionEarnsPositionBonus() {
        // 两个查询词只命中一个：位置越早加分越多（封顶 0.3）。
        float atStart = provider.calculateRelevanceScore(
                "alpha beta", "alpha world");
        String farText = "x".repeat(600) + " alpha";
        float farAway = provider.calculateRelevanceScore(
                "alpha beta", farText);
        assertTrue(atStart > farAway);
    }

    @Test
    void allPunctuationTermIsKeptAsIsWithoutBreakingScoring() {
        // 全标点词剥离后为空 → 原样返回（start==end 分支）。
        float score = provider.calculateRelevanceScore("... alpha", "... alpha");
        assertTrue(score > 0f);
    }

    @Test
    void lexicalFeatureCapTruncatesLongTexts() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            many.append("w").append(i).append(' ');
        }
        String text = many.toString();
        float selfSimilarity = provider.calculateTextSimilarity(text, text);
        assertEquals(1.0f, selfSimilarity);
    }

    @Test
    void mixedCjkLatinSegmentsAreSegmentedAndMatched() {
        String query = "深度学习deep模型model3结合";
        float selfSimilarity = provider.calculateTextSimilarity(query, query);
        assertEquals(1.0f, selfSimilarity);
        // 相同 CJK 段落在不同拉丁上下文中仍然产生交集。
        float partial = provider.calculateTextSimilarity(
                "深度学习deep", "深度学习DEEP4");
        assertTrue(partial > 0f);
    }

    @Test
    void cjkScriptFamilyTermsCountAsSimilarityTerms() {
        // 平假名/片假名/谚文/注音均按 CJK 处理：单字也可作为相似项。
        String kana = "モデル";
        String hangul = "한글";
        assertEquals(1.0f,
                provider.calculateTextSimilarity(kana, kana));
        assertEquals(1.0f,
                provider.calculateTextSimilarity(hangul, hangul));
        assertEquals(0f,
                provider.calculateTextSimilarity(kana, hangul));
    }

    @Test
    void diversityScoreUsesPrivateCandidateFeaturePath() {
        RetrievalResult self = result("alpha beta");
        RetrievalResult other = result("completely different content");
        RetrievalResult sibling = result("alpha beta gamma");

        float diversity = provider.calculateDiversityScore(
                "alpha beta", List.of(self, other, sibling));

        // 与相近兄弟的最大相似度 > 0 → 多样性 < 1。
        assertTrue(diversity < 1.0f);
        assertTrue(diversity >= 0f);
    }

    private RetrievalResult result(String text) {
        RetrievalResult result = new RetrievalResult();
        result.setChunkText(text);
        result.setScore(0.5f);
        return result;
    }
}
