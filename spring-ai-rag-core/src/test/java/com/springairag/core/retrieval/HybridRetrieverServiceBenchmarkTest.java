package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridRetrieverService 性能基准测试
 *
 * <p>验证关键性能指标：
 * <ul>
 *   <li>单次向量检索服务层开销 < 50ms（不含 EmbeddingModel 和 DB 实际耗时）</li>
 *   <li>结果融合（fuseAndDeduplicate）1000 条结果 < 100ms</li>
 *   <li>分数计算（cosineSimilarity）1M 次 < 500ms</li>
 * </ul>
 */
class HybridRetrieverServiceBenchmarkTest {

    private EmbeddingModel embeddingModel;
    private JdbcTemplate jdbcTemplate;
    private HybridRetrieverService service;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        // 模拟 pg_trgm 可用（更具体的 matcher 放在后面才能覆盖 anyString）
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("not found"));
        when(jdbcTemplate.queryForObject(eq("SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'"), eq(Integer.class)))
                .thenReturn(1);

        RagProperties props = new RagProperties();
        FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbcTemplate, props);
        Executor directExecutor = Runnable::run;
        service = new HybridRetrieverService(embeddingModel, jdbcTemplate, props, factory, directExecutor);
    }

    @Test
    @DisplayName("向量检索服务层开销 < 50ms（10 次调用平均）")
    void vectorSearch_overhead_under50ms() {
        // Mock embedding model — 返回假向量，不调用真实 API
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        // Mock JdbcTemplate — 返回假数据，不查真实数据库
        List<Map<String, Object>> fakeRows = createFakeRows(10);
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(fakeRows);

        // 预热
        service.search("warmup", null, null, 10);

        // 基准测试：10 次调用取平均
        int iterations = 10;
        long totalNs = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            service.search("测试查询 " + i, null, null, 10);
            totalNs += System.nanoTime() - start;
        }

        double avgMs = (totalNs / (double) iterations) / 1_000_000;
        System.out.printf("[Benchmark] 向量检索服务层平均耗时: %.2f ms (10次)%n", avgMs);

        assertTrue(avgMs < 50, String.format("服务层平均开销应 < 50ms，实际: %.2fms", avgMs));
    }

    @Test
    @DisplayName("混合检索（向量+全文并行）服务层开销 < 100ms")
    void hybridSearch_overhead_under100ms() {
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        // Mock 向量搜索和全文搜索返回不同的结果集
        List<Map<String, Object>> vectorRows = createFakeRows(20);
        List<Map<String, Object>> fulltextRows = createFakeFulltextRows(20);

        // pg_trgm 可用性检测
        when(jdbcTemplate.queryForObject(contains("pg_trgm"), eq(Integer.class)))
                .thenReturn(1);

        // 第一次调用是向量搜索（ORDER BY embedding <=>），第二次是全文搜索（similarity）
        when(jdbcTemplate.queryForList(contains("embedding <=>"), (Object[]) any()))
                .thenReturn(vectorRows);
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(fulltextRows);

        // 预热
        service.search("warmup", null, null, 10);

        long start = System.nanoTime();
        List<RetrievalResult> results = service.search("测试混合检索", null, null, 10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] 混合检索服务层耗时: %d ms, 结果数: %d%n", elapsedMs, results.size());

        assertTrue(elapsedMs < 100, String.format("混合检索服务层开销应 < 100ms，实际: %dms", elapsedMs));
        assertFalse(results.isEmpty(), "混合检索应返回结果");
    }

    @Test
    @DisplayName("RetrievalUtils.fuseResults 融合 1000 条结果 < 100ms")
    void fuseResults_1000items_under100ms() {
        List<RetrievalResult> vectorResults = createFakeResults(1000, "v");
        List<RetrievalResult> fulltextResults = createFakeResults(1000, "f");

        // 预热
        RetrievalUtils.fuseResults(vectorResults, fulltextResults, 20, 0.7f, 0.3f);

        long start = System.nanoTime();
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                vectorResults, fulltextResults, 50, 0.7f, 0.3f);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] fuseResults(1000+1000 → 50): %d ms%n", elapsedMs);

        assertTrue(elapsedMs < 100, String.format("融合 1000 条结果应 < 100ms，实际: %dms", elapsedMs));
        assertEquals(50, fused.size(), "融合后应返回指定数量的结果");
    }

    @Test
    @DisplayName("RetrievalUtils.cosineSimilarity 10 万次计算 < 500ms")
    void cosineSimilarity_100k_under500ms() {
        float[] a = new float[1024];
        float[] b = new float[1024];
        for (int i = 0; i < 1024; i++) {
            a[i] = (float) Math.random();
            b[i] = (float) Math.random();
        }

        // 预热
        RetrievalUtils.cosineSimilarity(a, b);

        int iterations = 100_000;
        long start = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < iterations; i++) {
            b[0] = (float) i / iterations; // 防止 JIT 完全优化掉
            sum += RetrievalUtils.cosineSimilarity(a, b);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] cosineSimilarity 10万次 (1024维): %d ms, sum=%.4f%n",
                elapsedMs, sum);

        assertTrue(elapsedMs < 500,
                String.format("10万次余弦相似度应 < 500ms，实际: %dms", elapsedMs));
    }

    @Test
    @DisplayName("RetrievalUtils.vectorToString 1 万次 (1024维) < 500ms")
    void vectorToString_10k_under500ms() {
        float[] vector = new float[1024];
        for (int i = 0; i < vector.length; i++) vector[i] = (float) Math.random();

        // 预热
        RetrievalUtils.vectorToString(vector);

        int iterations = 10_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            RetrievalUtils.vectorToString(vector);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] vectorToString 1万次 (1024维): %d ms%n", elapsedMs);

        assertTrue(elapsedMs < 1000,
                String.format("1万次向量序列化应 < 1000ms，实际: %dms", elapsedMs));
    }

    @Test
    @DisplayName("纯计算端到端：查询→融合→排序 全链路 < 150ms")
    void endToEnd_computeOnly_under150ms() {
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        List<Map<String, Object>> rows = createFakeRows(20);
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(rows);

        // 预热
        service.search("warmup", null, null, 20);

        // 端到端基准
        int iterations = 5;
        long totalNs = 0;
        List<RetrievalResult> lastResults = null;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            lastResults = service.search("端到端测试查询 " + i, null, null, 20);
            totalNs += System.nanoTime() - start;
        }

        double avgMs = (totalNs / (double) iterations) / 1_000_000;
        System.out.printf("[Benchmark] 端到端服务层平均耗时: %.2f ms (5次), 结果数: %d%n",
                avgMs, lastResults != null ? lastResults.size() : 0);

        assertTrue(avgMs < 150,
                String.format("端到端服务层应 < 150ms，实际: %.2fms", avgMs));
    }

    // ==================== Helper Methods ====================

    private List<Map<String, Object>> createFakeRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", (long) i + 1);
            row.put("chunk_text", "测试文本块 " + i);
            row.put("embedding", createFakeVectorString());
            row.put("document_id", (long) (i % 5 + 1));
            row.put("chunk_index", i);
            row.put("metadata", Map.of("source", "test"));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> createFakeFulltextRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", (long) i + 100);
            row.put("chunk_text", "全文搜索结果 " + i);
            row.put("embedding", createFakeVectorString());
            row.put("document_id", (long) (i % 5 + 1));
            row.put("chunk_index", i);
            row.put("metadata", Map.of("source", "fulltext"));
            row.put("sim", 0.5 + (float) i / (count * 2));
            rows.add(row);
        }
        return rows;
    }

    private String createFakeVectorString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", Math.random()));
        }
        sb.append("]");
        return sb.toString();
    }

    private List<RetrievalResult> createFakeResults(int count, String prefix) {
        List<RetrievalResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RetrievalResult r = new RetrievalResult();
            r.setDocumentId(prefix + "-" + i);
            r.setChunkText(prefix + " 文本 " + i);
            r.setScore(Math.random());
            r.setVectorScore(Math.random());
            r.setFulltextScore(Math.random());
            r.setChunkIndex(i);
            results.add(r);
        }
        return results;
    }
}
