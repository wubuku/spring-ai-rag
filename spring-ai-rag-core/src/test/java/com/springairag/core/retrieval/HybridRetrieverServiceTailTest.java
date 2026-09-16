package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * HybridRetrieverService 长尾（Batch 444）：isTimeout 的因果链遍
 * 历、normalizeErrorCode 的解包与空名兜底、candidateRetrieval
 * Limit 的重排候选池放大与各类原样返回分支。
 */
class HybridRetrieverServiceTailTest {

    private HybridRetrieverService serviceWith(RagProperties properties) {
        return new HybridRetrieverService(
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(JdbcTemplate.class),
                properties,
                null,
                Runnable::run);
    }

    private static boolean isTimeout(Throwable error) throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "isTimeout", Throwable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, error);
    }

    private static String normalizeErrorCode(Throwable error) throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "normalizeErrorCode", Throwable.class);
        method.setAccessible(true);
        return (String) method.invoke(null, error);
    }

    private int candidateLimit(RagProperties properties, RetrievalConfig config,
                               int requested) throws Exception {
        HybridRetrieverService service = serviceWith(properties);
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "candidateRetrievalLimit", RetrievalConfig.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(service, config, requested);
    }

    @Test
    void isTimeoutWalksCauseChain() throws Exception {
        assertTrue(isTimeout(new TimeoutException("t")));
        assertTrue(isTimeout(new CompletionException(new TimeoutException("t"))));
        assertTrue(isTimeout(new RuntimeException("wrap",
                new TimeoutException("deep"))));
        assertFalse(isTimeout(new IllegalStateException("other")));
        assertFalse(isTimeout(new RuntimeException()));
    }

    @Test
    void normalizeErrorCodeUnwrapsAndFallsBack() throws Exception {
        assertEquals("TimeoutException", normalizeErrorCode(
                new CompletionException(new TimeoutException("t"))));
        assertEquals("IllegalStateException", normalizeErrorCode(
                new CompletionException(new IllegalStateException("x"))));
        assertEquals("IllegalStateException", normalizeErrorCode(
                new IllegalStateException("direct")));
        // 匿名子类无简单名 → ERROR 兜底。
        RuntimeException anonymous = new RuntimeException("anon") { };
        assertEquals("ERROR", normalizeErrorCode(anonymous));
    }

    @Test
    void candidateLimitAppliesOnlyWhenRerankActive() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setEnabled(true);
        properties.getRerank().setCandidateLimit(20);
        HybridRetrieverService service = serviceWith(properties);

        RetrievalConfig rerankConfig = RetrievalConfig.builder()
                .maxResults(5).useRerank(true).build();
        RetrievalConfig noRerank = RetrievalConfig.builder()
                .maxResults(5).useRerank(false).build();

        // 重排激活 → 候选池放大到 rerank.candidateLimit。
        assertEquals(20, candidateLimit(properties, rerankConfig, 5));
        // 无重排 → 原样返回。
        assertEquals(5, candidateLimit(properties, noRerank, 5));
        // 越界请求原样返回。
        assertEquals(0, candidateLimit(properties, rerankConfig, 0));
        assertEquals(200, candidateLimit(properties, rerankConfig, 200));
    }

    @Test
    void candidateLimitPassesThroughWhenRerankProviderDisabled() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getRerank().setProvider("off");
        properties.getRerank().setEnabled(true);
        properties.getRerank().setCandidateLimit(20);
        HybridRetrieverService service = serviceWith(properties);

        RetrievalConfig rerankConfig = RetrievalConfig.builder()
                .maxResults(5).useRerank(true).build();
        assertEquals(5, candidateLimit(properties, rerankConfig, 5));
    }
}
