package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HeuristicRerankProvider 长尾（Batch 555，JaCoCo 驱动）：null 配
 * 置回退、isAvailable、空结果直通、NaN 分数防护、diversity 自身跳
 * 过、MAX_LEXICAL_FEATURES 截断。
 */
class HeuristicRerankProviderLimitsTailTest {

    private static RetrievalResult result(String text, double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(text);
        result.setChunkText(text);
        result.setScore(score);
        return result;
    }

    @Test
    void nullConfigFallsBackToDefaultsAndProviderIsAvailable() {
        var provider = new HeuristicRerankProvider(null);

        assertTrue(provider.isAvailable());
        assertEquals("heuristic", provider.getName());
    }

    @Test
    void rerankPassesThroughNullAndEmptyResults() {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());

        assertEquals(null, provider.rerank("q", null, 5));
        assertTrue(provider.rerank("q", List.of(), 5).isEmpty());
    }

    @Test
    void rerankGuardsAgainstNaNRawScores() {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());
        var nanResult = result("matching", Double.NaN);
        var normal = result("other content", 0.9);

        List<RetrievalResult> reranked =
                provider.rerank("matching", List.of(nanResult, normal), 10);

        assertEquals(2, reranked.size());
        // NaN 分数被归零处理，不再产生 NaN 传播。
        for (RetrievalResult item : reranked) {
            assertTrue(!Double.isNaN(item.getScore()));
        }
    }

    @Test
    void diversityScoreSkipsSelfEntry() {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());
        List<RetrievalResult> all = List.of(
                result("alpha beta gamma", 0.9),
                result("alpha beta gamma", 0.8));

        // 两条完全相同文本：第一条被 selfSkipped，第二条同样一致 →
        // 相似度仍为 1 → diversity = 0。
        assertEquals(0.0f, provider.calculateDiversityScore(
                "alpha beta gamma", all));
    }

    @Test
    void diversityScoreExcludesMaxSimilarityOfDistinctSiblings() {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());
        List<RetrievalResult> all = List.of(
                result("alpha beta gamma", 0.9),
                result("alpha beta delta", 0.8));

        float diversity = provider.calculateDiversityScore(
                "alpha beta gamma", all);

        // 与 delta 变体部分相似 → diversity 介于 0 与 1 之间。
        assertTrue(diversity > 0.0f);
        assertTrue(diversity < 1.0f);
    }

    @Test
    void diversityScoreSingleResultListYieldsFullDiversity() {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());

        assertEquals(1.0f, provider.calculateDiversityScore(
                "unique text", List.of(result("unique text", 0.5))));
    }

    @Test
    void rerankHandlesLongLexicalFeatureQueriesWithinCap() throws Exception {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());
        // 超长 CJK+拉丁混合查询触发 MAX_LEXICAL_FEATURES 截断分支。
        String longQuery = ("混合检索质量优化 alpha beta gamma delta epsilon "
                + "zeta eta theta iota kappa lambda mu nu").repeat(6);
        List<RetrievalResult> results = List.of(
                result("混合检索质量优化 related", 0.9),
                result("alpha beta gamma related", 0.8),
                result("unrelated content", 0.7));

        List<RetrievalResult> reranked =
                provider.rerank(longQuery, results, 10);

        assertEquals(3, reranked.size());
        // 分数仍在 [0,1] 附近：NaN/无限值未出现。
        for (RetrievalResult item : reranked) {
            assertTrue(Double.isFinite(item.getScore()));
        }
    }

    @Test
    void rerankWithRankingDepthZeroUsesFullResults() throws Exception {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());
        List<RetrievalResult> results = List.of(
                result("alpha content", 0.9),
                result("beta content", 0.8));

        // rankingDepth=0 → 使用完整结果数作为窗口。
        assertEquals(2, provider.rerank("alpha", results, 0).size());
    }

    @Test
    void rerankPreservesIdentityForBestMatch() throws Exception {
        var provider = new HeuristicRerankProvider(new RagRerankProperties());
        RetrievalResult best = result("alpha beta gamma", 0.95);

        List<RetrievalResult> reranked = provider.rerank(
                "alpha beta", List.of(result("other text", 0.5), best), 10);

        assertEquals("alpha beta gamma",
                reranked.getFirst().getDocumentId());
    }
}
