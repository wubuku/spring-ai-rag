package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReRankingService 测试
 */
class ReRankingServiceTest {

    private static ReRankingService createService(boolean enabled, float diversityWeight) {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(enabled);
        props.getRerank().setDiversityWeight(diversityWeight);
        return new ReRankingService(props);
    }

    private final ReRankingService service = createService(false, 0.2f);

    @Test
    void rerank_disabled_returnsOriginalOrder() {
        // service is created with enabled=false
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "low relevance", 0.3),
                createResult("doc-2", "high relevance", 0.9)
        );

        List<RetrievalResult> reranked = service.rerank("query", results, 5);
        assertEquals(results, reranked, "rerank 禁用时应返回原始列表");
    }

    @Test
    void rerank_enabled_reordersByRelevance() {
        ReRankingService enabledService = createService(true, 0.2f);

        List<RetrievalResult> results = new ArrayList<>();
        results.add(createResult("doc-1", "Spring 框架是一个 Java 框架", 0.5));
        results.add(createResult("doc-2", "Spring Boot 提供自动配置功能", 0.8));
        results.add(createResult("doc-3", "完全无关的内容", 0.3));

        List<RetrievalResult> reranked = enabledService.rerank("Spring Boot", results, 5);
        assertNotNull(reranked);
        assertFalse(reranked.isEmpty());

        // 重排后分数应降序
        for (int i = 0; i < reranked.size() - 1; i++) {
            assertTrue(reranked.get(i).getScore() >= reranked.get(i + 1).getScore(),
                    "重排结果应按分数降序排列");
        }
    }

    @Test
    void rerank_nullResults_returnsNull() {
        ReRankingService enabledService = createService(true, 0.2f);
        assertNull(enabledService.rerank("query", null, 5));
    }

    @Test
    void rerank_emptyList_returnsEmpty() {
        List<RetrievalResult> reranked = service.rerank("query", new ArrayList<>(), 5);
        assertNotNull(reranked);
        assertTrue(reranked.isEmpty());
    }

    @Test
    void rerank_singleResult_returnsSame() {
        ReRankingService enabledService = createService(true, 0.2f);

        List<RetrievalResult> results = List.of(
                createResult("doc-1", "测试内容", 0.9));

        List<RetrievalResult> reranked = enabledService.rerank("测试", results, 5);
        assertEquals(1, reranked.size());
        assertEquals("doc-1", reranked.get(0).getDocumentId());
    }

    @Test
    void rerank_respectsMaxResults() {
        ReRankingService enabledService = createService(true, 0.2f);

        List<RetrievalResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(createResult("doc-" + i, "content " + i, 0.5 + i * 0.05));
        }

        List<RetrievalResult> reranked = enabledService.rerank("query", results, 3);
        assertTrue(reranked.size() <= 3, "应不超过 maxResults");
    }

    // ========== calculateRelevanceScore ==========

    @Test
    void relevanceScore_fullMatch_highScore() {
        float score = service.calculateRelevanceScore(
                "Spring Boot", "Spring Boot 是一个框架");
        assertTrue(score > 0.5f, "完整匹配应得高分");
    }

    @Test
    void relevanceScore_partialMatch() {
        float score = service.calculateRelevanceScore(
                "Spring Boot", "Spring 是一个框架");
        assertTrue(score > 0, "部分匹配应得分");
    }

    @Test
    void relevanceScore_noMatch_lowScore() {
        float score = service.calculateRelevanceScore(
                "Spring Boot", "完全无关的内容");
        assertTrue(score < 0.5f, "不匹配应得低分");
    }

    @Test
    void relevanceScore_nullQuery_returnsZero() {
        assertEquals(0f, service.calculateRelevanceScore(null, "text"));
    }

    @Test
    void relevanceScore_nullText_returnsZero() {
        assertEquals(0f, service.calculateRelevanceScore("query", null));
    }

    @Test
    void relevanceScore_earlyPosition_bonus() {
        float earlyMatch = service.calculateRelevanceScore(
                "keyword", "keyword appears early in text");
        float lateMatch = service.calculateRelevanceScore(
                "keyword", "this is a very long text that eventually contains keyword at the end");
        assertTrue(earlyMatch >= lateMatch, "关键词出现越早得分越高");
    }

    // ========== calculateDiversityScore ==========

    @Test
    void diversityScore_singleResult_returnsOne() {
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "only one", 0.5));
        float score = service.calculateDiversityScore("only one", results);
        assertEquals(1.0f, score, 1e-6);
    }

    @Test
    void diversityScore_similarTexts_lowDiversity() {
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "Spring Boot framework", 0.5),
                createResult("doc-2", "Spring Boot framework", 0.6)
        );
        // 用不同的 input text，这样才会与 results 计算相似度
        float score = service.calculateDiversityScore("Spring framework", results);
        assertTrue(score < 1.0f, "与相似文本比较应降低多样性分数");
    }

    @Test
    void diversityScore_differentTexts_highDiversity() {
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "apple banana cherry", 0.5),
                createResult("doc-2", "dog cat bird fish", 0.6)
        );
        float score = service.calculateDiversityScore("xyz uvw", results);
        assertTrue(score > 0.5f, "完全不同文本应有高多样性");
    }

    @Test
    void diversityScore_nullText_returnsZero() {
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "Spring Boot", 0.5),
                createResult("doc-2", "Spring Framework", 0.6)
        );
        assertEquals(0f, service.calculateDiversityScore(null, results));
    }

    // ========== calculateTextSimilarity ==========

    @Test
    void textSimilarity_identical_returnsOne() {
        float sim = service.calculateTextSimilarity("hello world", "hello world");
        assertEquals(1.0f, sim, 1e-6);
    }

    @Test
    void textSimilarity_disjoint_returnsZero() {
        float sim = service.calculateTextSimilarity("apple banana", "cat dog");
        assertEquals(0.0f, sim, 1e-6);
    }

    @Test
    void textSimilarity_partialOverlap() {
        float sim = service.calculateTextSimilarity(
                "Spring Boot framework",
                "Spring framework tutorial");
        assertTrue(sim > 0 && sim < 1, "部分重叠应在 (0, 1) 之间");
    }

    @Test
    void textSimilarity_null_returnsZero() {
        assertEquals(0f, service.calculateTextSimilarity(null, "text"));
        assertEquals(0f, service.calculateTextSimilarity("text", null));
    }

    @Test
    void textSimilarity_shortWords_ignored() {
        // 长度 < 2 的单词应被忽略
        float sim = service.calculateTextSimilarity("a b c", "a b c");
        assertEquals(0f, sim, "单字符词应被忽略");
    }

    // ========== Helper ==========

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        r.setVectorScore(score);
        r.setFulltextScore(score);
        return r;
    }
}
