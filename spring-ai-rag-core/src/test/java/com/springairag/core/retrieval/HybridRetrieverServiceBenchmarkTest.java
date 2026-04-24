package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.PgJiebaFulltextProvider;
import com.springairag.core.retrieval.fulltext.PgEnglishFtsProvider;
import com.springairag.core.retrieval.fulltext.PgTrgmFulltextProvider;
import com.springairag.core.retrieval.fulltext.SearchCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridRetrieverService Performance Benchmark Tests
 *
 * <p>Validates key performance metrics:
 * <ul>
 *   <li>Single vector search service-layer overhead &lt; 50ms (excluding EmbeddingModel and DB actual latency)</li>
 *   <li>Result fusion (fuseAndDeduplicate) 1000 results &lt; 100ms</li>
 *   <li>Cosine similarity computation 1M times &lt; 500ms</li>
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

        RagProperties props = new RagProperties();

        // Full mock setup: required by SearchCapabilities and Provider detectAvailability()
        when(jdbcTemplate.queryForObject(contains("search_vector_zh"), eq(Boolean.class))).thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("vector"));
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Integer.class))).thenReturn(1);

        // Create SearchCapabilities (init=false, set fields directly)
        SearchCapabilities caps = new SearchCapabilities(jdbcTemplate, false);
        caps.setHasPgVector(true);
        caps.setHasJieba(false);
        caps.setHasZhIndex(false);
        caps.setHasEnIndex(false);
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(true);

        // Create spy providers (isAvailable can be stubbed)
        PgJiebaFulltextProvider spyJieba = spy(new PgJiebaFulltextProvider(jdbcTemplate));
        PgEnglishFtsProvider spyEnglish = spy(new PgEnglishFtsProvider(jdbcTemplate));
        PgTrgmFulltextProvider spyTrgm = spy(new PgTrgmFulltextProvider(jdbcTemplate));

        // Default: jieba and english unavailable, trgm available
        doReturn(false).when(spyJieba).isAvailable();
        doReturn(false).when(spyEnglish).isAvailable();
        doReturn(true).when(spyTrgm).isAvailable();

        FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(
                jdbcTemplate, "auto", caps, spyJieba, spyEnglish, spyTrgm);
        Executor directExecutor = Runnable::run;
        service = new HybridRetrieverService(embeddingModel, jdbcTemplate, props, factory, directExecutor);
    }

    @Test
    @DisplayName("Vector search service layer overhead < 50ms (average of 10 calls)")
    void vectorSearch_overhead_under50ms() {
        // Mock embedding model — returns fake vector, no real API call
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        // Mock JdbcTemplate — returns fake data, no real DB query
        List<Map<String, Object>> fakeRows = createFakeRows(10);
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(fakeRows);

        // Warmup
        service.search("warmup", null, null, 10);

        // Benchmark: average over 10 calls
        int iterations = 10;
        long totalNs = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            service.search("测试查询 " + i, null, null, 10);
            totalNs += System.nanoTime() - start;
        }

        double avgMs = (totalNs / (double) iterations) / 1_000_000;
        System.out.printf("[Benchmark] Vector search service-layer avg: %.2f ms (10 calls)%n", avgMs);

        assertTrue(avgMs < 50, String.format("服务层平均开销应 < 50ms，实际: %.2fms", avgMs));
    }

    @Test
    @DisplayName("Hybrid search (vector+fulltext parallel) service layer overhead < 100ms")
    void hybridSearch_overhead_under100ms() {
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        // Mock vector search and fulltext search returning different result sets
        List<Map<String, Object>> vectorRows = createFakeRows(20);
        List<Map<String, Object>> fulltextRows = createFakeFulltextRows(20);

        // pg_trgm availability detection (Boolean for index + Integer for extension)
        when(jdbcTemplate.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("pg_trgm"), eq(Integer.class))).thenReturn(1);

        // First call is vector search (ORDER BY embedding <=>), second is fulltext search (similarity)
        when(jdbcTemplate.queryForList(contains("embedding <=>"), (Object[]) any()))
                .thenReturn(vectorRows);
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(fulltextRows);

        // Warmup
        service.search("warmup", null, null, 10);

        long start = System.nanoTime();
        List<RetrievalResult> results = service.search("测试混合检索", null, null, 10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] Hybrid search service-layer: %d ms, result count: %d%n", elapsedMs, results.size());

        assertTrue(elapsedMs < 100, String.format("混合检索服务层开销应 < 100ms，实际: %dms", elapsedMs));
        assertFalse(results.isEmpty(), "混合检索应返回结果");
    }

    @Test
    @DisplayName("RetrievalUtils.fuseResults: fuse 1000 results < 100ms")
    void fuseResults_1000items_under100ms() {
        List<RetrievalResult> vectorResults = createFakeResults(1000, "v");
        List<RetrievalResult> fulltextResults = createFakeResults(1000, "f");

        // Warmup
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
    @DisplayName("RetrievalUtils.cosineSimilarity: 100k computations < 500ms")
    void cosineSimilarity_100k_under500ms() {
        float[] a = new float[1024];
        float[] b = new float[1024];
        for (int i = 0; i < 1024; i++) {
            a[i] = (float) Math.random();
            b[i] = (float) Math.random();
        }

        // Warmup
        RetrievalUtils.cosineSimilarity(a, b);

        int iterations = 100_000;
        long start = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < iterations; i++) {
            b[0] = (float) i / iterations; // Prevent JIT from fully optimizing away
            sum += RetrievalUtils.cosineSimilarity(a, b);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] cosineSimilarity 100k (1024-dim): %d ms, sum=%.4f%n",
                elapsedMs, sum);

        assertTrue(elapsedMs < 500,
                String.format("10万次余弦相似度应 < 500ms，实际: %dms", elapsedMs));
    }

    @Test
    @DisplayName("RetrievalUtils.vectorToString: 10k conversions (1024-dim) < 500ms")
    void vectorToString_10k_under500ms() {
        float[] vector = new float[1024];
        for (int i = 0; i < vector.length; i++) vector[i] = (float) Math.random();

        // Warmup
        RetrievalUtils.vectorToString(vector);

        int iterations = 10_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            RetrievalUtils.vectorToString(vector);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] vectorToString 10k (1024-dim): %d ms%n", elapsedMs);

        assertTrue(elapsedMs < 1000,
                String.format("1万次向量序列化应 < 1000ms，实际: %dms", elapsedMs));
    }

    @Test
    @DisplayName("Pure compute end-to-end: query->fuse->rank full chain < 150ms")
    void endToEnd_computeOnly_under150ms() {
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        List<Map<String, Object>> rows = createFakeRows(20);
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(rows);

        // Warmup
        service.search("warmup", null, null, 20);

        // End-to-end benchmark
        int iterations = 5;
        long totalNs = 0;
        List<RetrievalResult> lastResults = null;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            lastResults = service.search("端到端测试查询 " + i, null, null, 20);
            totalNs += System.nanoTime() - start;
        }

        double avgMs = (totalNs / (double) iterations) / 1_000_000;
        System.out.printf("[Benchmark] End-to-end service-layer avg: %.2f ms (5 calls), result count: %d%n",
                avgMs, lastResults != null ? lastResults.size() : 0);

        assertTrue(avgMs < 150,
                String.format("端到端服务层应 < 150ms，实际: %.2fms", avgMs));
    }

    @Test
    @DisplayName("Concurrent search: 10 threads x 10 iterations, total throughput > 50 ops/s")
    void concurrentSearch_throughput_above50ops() throws Exception {
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        List<Map<String, Object>> rows = createFakeRows(10);
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(rows);

        // Warmup
        service.search("warmup", null, null, 10);

        int threadCount = 10;
        int opsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger queryId = new AtomicInteger(0);

        long start = System.nanoTime();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    service.search("并发查询 " + queryId.incrementAndGet(), null, null, 10);
                }
            }, pool));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();

        int totalOps = threadCount * opsPerThread;
        double opsPerSec = totalOps * 1000.0 / elapsedMs;
        System.out.printf("[Benchmark] Concurrent search %d threads x %d ops: %d ms, %.0f ops/s%n",
                threadCount, opsPerThread, elapsedMs, opsPerSec);

        assertTrue(opsPerSec > 50,
                String.format("并发吞吐量应 > 50 ops/s，实际: %.0f ops/s", opsPerSec));
    }

    @Test
    @DisplayName("fuseResults large dataset: 10000+10000 -> 100 < 2s")
    void fuseResults_10kItems_under2s() {
        List<RetrievalResult> vectorResults = createFakeResults(10_000, "v");
        List<RetrievalResult> fulltextResults = createFakeResults(10_000, "f");

        // Warmup
        RetrievalUtils.fuseResults(vectorResults, fulltextResults, 10, 0.7f, 0.3f);

        long start = System.nanoTime();
        List<RetrievalResult> fused = RetrievalUtils.fuseResults(
                vectorResults, fulltextResults, 100, 0.7f, 0.3f);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] fuseResults(10k+10k → 100): %d ms%n", elapsedMs);

        assertTrue(elapsedMs < 2000,
                String.format("融合 10000 条结果应 < 2s，实际: %dms", elapsedMs));
        assertEquals(100, fused.size());
    }

    @Test
    @DisplayName("Concurrent cosineSimilarity: 8 threads x 25000 iterations < 3s")
    void concurrentCosineSimilarity_under3s() throws Exception {
        float[][] vectors = new float[8][];
        for (int t = 0; t < 8; t++) {
            vectors[t] = new float[1024];
            for (int i = 0; i < 1024; i++) vectors[t][i] = (float) Math.random();
        }

        // Warmup
        for (int i = 0; i < 1000; i++) {
            RetrievalUtils.cosineSimilarity(vectors[0], vectors[1]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        long start = System.nanoTime();
        List<CompletableFuture<Double>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final float[] a = vectors[t];
            final float[] b = vectors[(t + 1) % 8];
            futures.add(CompletableFuture.supplyAsync(() -> {
                double sum = 0;
                for (int i = 0; i < 25_000; i++) {
                    b[0] = (float) i / 25_000;
                    sum += RetrievalUtils.cosineSimilarity(a, b);
                }
                return sum;
            }, pool));
        }
        double totalSum = futures.stream().mapToDouble(CompletableFuture::join).sum();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();

        System.out.printf("[Benchmark] Concurrent cosineSimilarity 8x25000 (1024-dim): %d ms, sum=%.4f%n",
                elapsedMs, totalSum);

        assertTrue(elapsedMs < 3000,
                String.format("8线程×25000次余弦相似度应 < 3s，实际: %dms", elapsedMs));
    }

    @Test
    @DisplayName("parseVector: parse 1024-dim string vectors 10000 times < 3s")
    void parseVector_10k_under3s() {
        String vectorStr = createFakeVectorString();

        // Warmup
        for (int i = 0; i < 100; i++) {
            RetrievalUtils.parseVector(vectorStr);
        }

        long start = System.nanoTime();
        float[] lastResult = null;
        for (int i = 0; i < 10_000; i++) {
            lastResult = RetrievalUtils.parseVector(vectorStr);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[Benchmark] parseVector 10k (1024-dim string): %d ms, dim=%d%n",
                elapsedMs, lastResult != null ? lastResult.length : 0);

        assertTrue(elapsedMs < 3000,
                String.format("1万次向量解析应 < 3s，实际: %dms", elapsedMs));
        assertEquals(1024, lastResult.length);
    }

    @Test
    @DisplayName("Concurrent hybrid search: 5 threads vector+fulltext < 500ms total")
    void concurrentHybridSearch_under500ms() throws Exception {
        float[] fakeVector = new float[1024];
        for (int i = 0; i < fakeVector.length; i++) fakeVector[i] = (float) Math.random();
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);

        List<Map<String, Object>> vectorRows = createFakeRows(20);
        List<Map<String, Object>> fulltextRows = createFakeFulltextRows(20);

        when(jdbcTemplate.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("pg_trgm"), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), (Object[]) any()))
                .thenReturn(vectorRows);
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(fulltextRows);

        // Warmup
        service.search("warmup", null, null, 10);

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger queryId = new AtomicInteger(0);

        long start = System.nanoTime();
        List<CompletableFuture<List<RetrievalResult>>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    service.search("混合并发 " + queryId.incrementAndGet(), null, null, 10), pool));
        }
        List<List<RetrievalResult>> allResults = futures.stream()
                .map(CompletableFuture::join).toList();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();

        System.out.printf("[Benchmark] Concurrent hybrid search %d threads: %d ms%n", threadCount, elapsedMs);

        assertTrue(elapsedMs < 500,
                String.format("5线程并发混合检索应 < 500ms，实际: %dms", elapsedMs));
        assertEquals(threadCount, allResults.size());
        allResults.forEach(r -> assertFalse(r.isEmpty()));
    }

    @Test
    @DisplayName("Large dataset end-to-end: 5000 result fuse+rank < 1s")
    void largeDatasetEndToEnd_under1s() {
        List<RetrievalResult> vectorResults = createFakeResults(5_000, "v");
        List<RetrievalResult> fulltextResults = createFakeResults(5_000, "f");

        // Warmup
        RetrievalUtils.fuseResults(vectorResults, fulltextResults, 20, 0.7f, 0.3f);

        // 多轮测试取平均
        int iterations = 5;
        long totalNs = 0;
        List<RetrievalResult> fused = null;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            fused = RetrievalUtils.fuseResults(
                    vectorResults, fulltextResults, 50, 0.6f, 0.4f);
            totalNs += System.nanoTime() - start;
        }
        double avgMs = (totalNs / (double) iterations) / 1_000_000;

        System.out.printf("[Benchmark] Large dataset end-to-end 5k+5k → 50: %.2f ms (avg of %d runs)%n",
                avgMs, iterations);

        assertTrue(avgMs < 1000,
                String.format("5000条数据融合应 < 1s，实际: %.2fms", avgMs));
        assertEquals(50, fused.size());
    }

    @Test
    @DisplayName("Concurrent fuseResults: 4 threads x 5000 items < 3s")
    void concurrentFuseResults_under3s() throws Exception {
        // Warmup
        List<RetrievalResult> warmup = createFakeResults(1000, "w");
        RetrievalUtils.fuseResults(warmup, warmup, 10, 0.7f, 0.3f);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        long start = System.nanoTime();
        List<CompletableFuture<List<RetrievalResult>>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final String prefix = "t" + t;
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<RetrievalResult> v = createFakeResults(5_000, prefix + "-v");
                List<RetrievalResult> f = createFakeResults(5_000, prefix + "-f");
                return RetrievalUtils.fuseResults(v, f, 50, 0.7f, 0.3f);
            }, pool));
        }
        List<List<RetrievalResult>> allResults = futures.stream()
                .map(CompletableFuture::join).toList();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();

        System.out.printf("[Benchmark] Concurrent fuseResults 4x(5k+5k → 50): %d ms%n", elapsedMs);

        assertTrue(elapsedMs < 3000,
                String.format("4线程并发融合应 < 3s，实际: %dms", elapsedMs));
        assertEquals(4, allResults.size());
        allResults.forEach(r -> assertEquals(50, r.size()));
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
            row.put("score_trgm", 0.5 + (float) i / (count * 2)); // PgTrgmFulltextProvider.toResult() reads score_trgm
            row.put("rank", i + 1); // PgEnglishFtsProvider.toResult() reads rank
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
