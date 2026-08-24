package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.retrieval.rerank.RerankProvider;
import com.springairag.core.retrieval.rerank.RerankProviderFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReRankingService 测试
 */
class ReRankingServiceTest {

    private static ReRankingService createService(boolean enabled, float diversityWeight) {
        RagProperties props = new RagProperties();
        props.getRerank().setEnabled(enabled);
        props.getRerank().setDiversityWeight(diversityWeight);
        props.getRerank().setProvider("heuristic");
        return new ReRankingService(props, new RerankProviderFactory(props));
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

    @Test
    void rerank_truncatesProviderOutputToRequestedLimit() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        List<RetrievalResult> results = List.of(
                createResult("doc-1", "one", 0.9),
                createResult("doc-2", "two", 0.8),
                createResult("doc-3", "three", 0.7));
        RerankProvider provider = new RerankProvider() {
            @Override
            public String getName() {
                return "oversized-test";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> rerank(
                    String query, List<RetrievalResult> input, int maxResults) {
                return results;
            }
        };

        ReRankingService service = new ReRankingService(config, provider);

        assertEquals(2, service.rerank("query", results, 2).size());
    }

    @Test
    void rerank_rejectsNullProviderOutput() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        RerankProvider provider = new RerankProvider() {
            @Override
            public String getName() {
                return "null-test";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> rerank(
                    String query, List<RetrievalResult> input, int maxResults) {
                return null;
            }
        };

        ReRankingService service = new ReRankingService(config, provider);

        assertThrows(
                IllegalStateException.class,
                () -> service.rerank(
                        "query",
                        List.of(createResult("doc", "text", 0.5)),
                        1));
    }

    @Test
    void rerank_usesBoundedProviderDepthAndDiversifiesByActualProviderName() {
        RagRerankProperties config = enabledConfig(4, 1);
        RecordingProvider provider = new RecordingProvider("http");
        List<RetrievalResult> results = List.of(
                createResult("A", "a0", 0.99),
                createResult("A", "a1", 0.98),
                createResult("B", "b0", 0.97),
                createResult("C", "c0", 0.96),
                createResult("D", "d0", 0.95));
        ReRankingService reranking = new ReRankingService(config, provider);

        List<RetrievalResult> output = reranking.rerank("query", results, 3);

        assertEquals(4, provider.lastInput.size());
        assertEquals(4, provider.lastRankingDepth);
        assertEquals(List.of("A", "B", "C"), documentIds(output));
        assertSame(results.get(0), output.get(0));
        assertSame(results.get(2), output.get(1));
        assertSame(results.get(3), output.get(2));
    }

    @Test
    void rerank_capsAbnormallyLargeDirectInputAtCandidateLimit() {
        RagRerankProperties config = enabledConfig(100, 1);
        RecordingProvider provider = new RecordingProvider("heuristic");
        List<RetrievalResult> results = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            results.add(createResult(
                    "doc-" + index,
                    "content-" + index,
                    1.0 - index / 1000.0));
        }

        List<RetrievalResult> output =
                new ReRankingService(config, provider).rerank("query", results, 3);

        assertEquals(100, provider.lastInput.size());
        assertEquals(100, provider.lastRankingDepth);
        assertEquals(3, output.size());
    }

    @Test
    void rerank_doesNotExpandProviderWhenSelectorIsInactive() {
        List<RetrievalResult> results = List.of(
                createResult("A", "a0", 0.99),
                createResult("A", "a1", 0.98),
                createResult("A", "a2", 0.97),
                createResult("B", "b0", 0.96),
                createResult("C", "c0", 0.95));

        RagRerankProperties disabledByZero = enabledConfig(5, 0);
        RecordingProvider zeroProvider = new RecordingProvider("heuristic");
        List<RetrievalResult> zeroOutput =
                new ReRankingService(disabledByZero, zeroProvider)
                        .rerank("query", results, 3);
        assertProviderCall(zeroProvider, 5, 3);
        assertEquals(List.of("A", "A", "A"), documentIds(zeroOutput));

        RagRerankProperties disabledByFinalLimit = enabledConfig(5, 3);
        RecordingProvider finalLimitProvider = new RecordingProvider("heuristic");
        new ReRankingService(disabledByFinalLimit, finalLimitProvider)
                .rerank("query", results, 3);
        assertProviderCall(finalLimitProvider, 5, 3);

        RagRerankProperties disabledByCandidateLimit = enabledConfig(3, 1);
        RecordingProvider candidateLimitProvider =
                new RecordingProvider("heuristic");
        new ReRankingService(disabledByCandidateLimit, candidateLimitProvider)
                .rerank("query", results, 3);
        assertProviderCall(candidateLimitProvider, 5, 3);

        RagRerankProperties disabledByProvider = enabledConfig(5, 1);
        RecordingProvider noOpProvider = new RecordingProvider(" NoOp ");
        new ReRankingService(disabledByProvider, noOpProvider)
                .rerank("query", results, 3);
        assertProviderCall(noOpProvider, 5, 3);
    }

    @Test
    void rerank_doesNotInventResultsWhenProviderReturnsFewerItems() {
        RagRerankProperties config = enabledConfig(5, 1);
        RecordingProvider provider = new RecordingProvider(
                "heuristic",
                (input, depth) -> input.subList(0, 2));
        List<RetrievalResult> results = List.of(
                createResult("A", "a0", 0.99),
                createResult("A", "a1", 0.98),
                createResult("B", "b0", 0.97),
                createResult("C", "c0", 0.96),
                createResult("D", "d0", 0.95));

        List<RetrievalResult> output =
                new ReRankingService(config, provider).rerank("query", results, 3);

        assertEquals(2, output.size());
        assertEquals(List.of("A", "A"), documentIds(output));
    }

    @Test
    void rerank_truncatesProviderOutputToRankingDepthBeforeSelection() {
        RagRerankProperties config = enabledConfig(4, 1);
        List<RetrievalResult> results = List.of(
                createResult("A", "a0", 0.99),
                createResult("A", "a1", 0.98),
                createResult("B", "b0", 0.97),
                createResult("C", "c0", 0.96),
                createResult("D", "d0", 0.95));
        RecordingProvider provider = new RecordingProvider(
                "heuristic",
                (input, depth) -> results);

        List<RetrievalResult> output =
                new ReRankingService(config, provider).rerank("query", results, 3);

        assertProviderCall(provider, 4, 4);
        assertEquals(List.of("A", "B", "C"), documentIds(output));
    }

    @Test
    void rerank_improvesReciprocalRankForKeywordRelevantDocument() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        config.setDiversityWeight(0.2f);
        ReRankingService enabledService = new ReRankingService(
                config, new com.springairag.core.retrieval.rerank.HeuristicRerankProvider(config));

        RetrievalResult distractor = createResult(
                "doc-distractor", "generic framework overview", 0.60);
        RetrievalResult relevant = createResult(
                "doc-relevant",
                "Spring AI Advisor chain vector store embedding model RAG",
                0.55);
        List<RetrievalResult> baseline = List.of(distractor, relevant);

        List<RetrievalResult> quality = enabledService.rerank(
                "Spring AI advisor vector store", baseline, 2);

        double baselineMrr = reciprocalRank(baseline, "doc-relevant");
        double qualityMrr = reciprocalRank(quality, "doc-relevant");
        assertTrue(qualityMrr > baselineMrr,
                "Heuristic quality profile should improve MRR on the deterministic golden case");
        assertEquals("doc-relevant", quality.getFirst().getDocumentId());
    }

    @Test
    void rerank_improvesReciprocalRankForCjkRelevantDocument() {
        ReRankingService enabledService = createService(true, 0.2f);
        RetrievalResult distractor = createResult(
                "doc-distractor",
                "账户权限审批流程和用量账本统计",
                1.0);
        RetrievalResult relevant = createResult(
                "doc-relevant",
                "检索质量需要结合中文分词进行优化",
                0.99);
        List<RetrievalResult> baseline = List.of(distractor, relevant);

        List<RetrievalResult> quality = enabledService.rerank(
                "中文检索质量优化",
                baseline,
                2);

        assertTrue(
                reciprocalRank(quality, "doc-relevant")
                        > reciprocalRank(baseline, "doc-relevant"));
        assertEquals("doc-relevant", quality.getFirst().getDocumentId());
    }

    @Test
    void rerank_improvesReciprocalRankForTitleOnlyRelevantDocument() {
        ReRankingService enabledService = createService(true, 0.2f);
        RetrievalResult distractor = createResult(
                "doc-distractor",
                "shared neutral maintenance evidence",
                1.0);
        distractor.setTitle("Account approval handbook");
        RetrievalResult relevant = createResult(
                "doc-relevant",
                "shared neutral maintenance evidence",
                0.99);
        relevant.setTitle("ZX-9042 液压校准规范");
        List<RetrievalResult> baseline = List.of(distractor, relevant);

        List<RetrievalResult> quality = enabledService.rerank(
                "ZX-9042 液压校准",
                baseline,
                2);

        assertTrue(
                reciprocalRank(quality, "doc-relevant")
                        > reciprocalRank(baseline, "doc-relevant"));
        assertEquals("doc-relevant", quality.getFirst().getDocumentId());
    }

    private double reciprocalRank(List<RetrievalResult> results, String relevantId) {
        for (int i = 0; i < results.size(); i++) {
            if (relevantId.equals(results.get(i).getDocumentId())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
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

    private static RagRerankProperties enabledConfig(
            int candidateLimit,
            int preferredMaxChunksPerDocument) {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        config.setCandidateLimit(candidateLimit);
        config.setPreferredMaxChunksPerDocument(
                preferredMaxChunksPerDocument);
        return config;
    }

    private static List<String> documentIds(List<RetrievalResult> results) {
        return results.stream().map(RetrievalResult::getDocumentId).toList();
    }

    private static void assertProviderCall(
            RecordingProvider provider,
            int inputSize,
            int rankingDepth) {
        assertEquals(inputSize, provider.lastInput.size());
        assertEquals(rankingDepth, provider.lastRankingDepth);
    }

    private static final class RecordingProvider implements RerankProvider {

        private final String name;
        private final BiFunction<List<RetrievalResult>, Integer,
                List<RetrievalResult>> behavior;
        private List<RetrievalResult> lastInput;
        private int lastRankingDepth;

        private RecordingProvider(String name) {
            this(
                    name,
                    (input, depth) -> input.subList(
                            0, Math.min(input.size(), depth)));
        }

        private RecordingProvider(
                String name,
                BiFunction<List<RetrievalResult>, Integer,
                        List<RetrievalResult>> behavior) {
            this.name = name;
            this.behavior = behavior;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<RetrievalResult> rerank(
                String query,
                List<RetrievalResult> input,
                int rankingDepth) {
            this.lastInput = input;
            this.lastRankingDepth = rankingDepth;
            return behavior.apply(input, rankingDepth);
        }
    }
}
