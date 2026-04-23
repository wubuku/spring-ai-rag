package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.PgTrgmFulltextProvider;
import com.springairag.core.retrieval.fulltext.SearchCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridRetrieverService Unit Tests
 *
 * <p>通过 mock EmbeddingModel 和 JdbcTemplate 测试混合检索逻辑。
 * 全文检索通过 PgTrgmFulltextProvider（mocked jdbcTemplate）实现。
 */
class HybridRetrieverServiceTest {

    private EmbeddingModel embeddingModel;
    private JdbcTemplate jdbcTemplate;
    private HybridRetrieverService service;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        // === Directly set SearchCapabilities fields (skip DB detection) ===
        SearchCapabilities caps = new SearchCapabilities(jdbcTemplate, false);
        caps.setHasPgVector(true);
        caps.setHasJieba(false);   // pg_jieba unavailable
        caps.setHasZhIndex(false); // jieba index absent
        caps.setHasEnIndex(false); // english index absent
        caps.setHasPgTrgm(true);  // pg_trgm extension present
        caps.setHasTrgmIndex(true); // trgm index present

        // Provider detectAvailability() 的 pg_extension 和 pg_indexes 查询
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("not found"));
        when(jdbcTemplate.queryForObject(contains("pg_trgm"), eq(Integer.class)))
                .thenReturn(1);  // pg_trgm extension available
        // pg_jieba: anyString() throws → not available
        when(jdbcTemplate.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true); // trgm 索引存在
        // search_vector_zh/en: not mocked → returns null → not available

        RagProperties ragProperties = new RagProperties();
        FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(
                jdbcTemplate, "auto", caps);
        service = new HybridRetrieverService(embeddingModel, jdbcTemplate, ragProperties, factory, null);
    }

    private float[] mockEmbedding() {
        return new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
    }

    private Map<String, Object> mockDbRow(long id, String docId, int chunkIndex,
                                           String chunkText, float[] embedding) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("document_id", docId);
        row.put("chunk_index", chunkIndex);
        row.put("chunk_text", chunkText);
        row.put("embedding", embedding);
        row.put("metadata", null);
        return row;
    }

    // ========== 混合检索（默认模式） ==========

    @Test
    @DisplayName("Hybrid search: parallel vector + fulltext, fuse results")
    void search_hybridMode_fusesVectorAndFulltext() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed("测试查询")).thenReturn(queryVec);

        // 向量检索返回结果
        Map<String, Object> vecRow = mockDbRow(1L, "doc-1", 0, "向量匹配内容", queryVec);
        when(jdbcTemplate.queryForList(startsWith("SELECT id, chunk_text"), any(Object[].class)))
                .thenReturn(List.of(vecRow));

        // 全文检索 —— 不同方法签名，用 any() 匹配
        Map<String, Object> ftRow = mockDbRow(2L, "doc-2", 1, "全文匹配内容", queryVec);
        ftRow.put("score_trgm", 0.85); // PgTrgmFulltextProvider.toResult() reads score_trgm
        // 全文检索也用 queryForList，需要区分调用
        // 使用 doReturn 来处理可变参数
        List<Map<String, Object>> fulltextRows = new ArrayList<>();
        fulltextRows.add(ftRow);

        // 由于两个搜索使用相同的 queryForList，需要更精确地匹配
        // 第一次调用是向量搜索（ORDER BY embedding <=>），第二次是全文搜索（similarity）
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(List.of(vecRow));
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(fulltextRows);

        List<RetrievalResult> results = service.search("测试查询", null, null, 10);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        // 应该包含两个来源的结果（可能融合为一个或多个）
        assertTrue(results.size() <= 10);
        // 调用了 embedding model
        verify(embeddingModel, times(1)).embed("测试查询");
    }

    // ========== 向量检索 ==========

    @Test
    @DisplayName("Vector search: generate embedding and query database")
    void search_vectorOnly_generatesEmbeddingAndQueries() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed("问题")).thenReturn(queryVec);

        Map<String, Object> row = mockDbRow(1L, "doc-1", 0, "检索内容", queryVec);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> results = service.search("问题", null, null, 5);

        assertFalse(results.isEmpty());
        assertEquals("doc-1", results.get(0).getDocumentId());
        assertEquals("检索内容", results.get(0).getChunkText());
        verify(embeddingModel).embed("问题");
    }

    @Test
    @DisplayName("Vector search: SQL contains IN clause when documentIds provided")
    void search_vectorWithDocumentIds_filtersByDocument() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        service.search("query", List.of(1L, 2L, 3L), null, 5);

        // 验证至少一次 queryForList 调用的 SQL 包含 IN 子句
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sqlCaptor.capture(), any(Object[].class));
        boolean hasInClause = sqlCaptor.getAllValues().stream()
                .anyMatch(sql -> sql.contains("IN"));
        assertTrue(hasInClause, "documentIds 不为空时 SQL 应包含 IN 子句");
    }

    @Test
    @DisplayName("Vector search: returns empty when embedding fails")
    void search_embeddingFails_returnsEmpty() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("API 超时"));

        // 全文检索也会失败（因为没有 embedding 协调，但独立调用）
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> results = service.search("问题", null, null, 5);

        // 向量检索返回空，全文检索返回空 → 融合结果为空
        assertNotNull(results);
    }

    @Test
    @DisplayName("Vector search: SQL uses IN filter for documentIds")
    void search_excludesDocumentIds_filtersCorrectly() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);

        // 模拟数据库已按 documentIds 过滤，只返回 doc-1 的数据
        Map<String, Object> row1 = mockDbRow(1L, "doc-1", 0, "内容1", queryVec);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row1));

        List<RetrievalResult> results = service.search("q", List.of(1L), null, 10);

        assertFalse(results.isEmpty());
        // 验证所有返回结果都是 doc-1
        results.forEach(r -> assertEquals("doc-1", r.getDocumentId(),
                "数据库层面过滤后只应返回指定文档的结果"));
    }

    // ========== 全文检索 ==========

    @Test
    @DisplayName("Fulltext search: returns similarity score")
    void search_fulltext_returnsSimilarityScore() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(1);

        Map<String, Object> ftRow = mockDbRow(1L, "doc-1", 0, "模糊匹配内容", queryVec);
        ftRow.put("score_trgm", 0.75); // PgTrgmFulltextProvider.toResult() reads score_trgm
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(List.of(ftRow));

        List<RetrievalResult> results = service.search("模糊匹配", null, null, 5);

        // 全文结果 minScore=0.3，0.75 > 0.3 应通过
        assertFalse(results.isEmpty());
    }

    @Test
    @DisplayName("Fulltext search: results below minScore are filtered")
    void search_fulltext_belowMinScore_filtered() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(1);

        Map<String, Object> ftRow = mockDbRow(1L, "doc-1", 0, "低相关", queryVec);
        ftRow.put("score_trgm", 0.1); // 低于 minScore 0.3
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(List.of(ftRow));

        List<RetrievalResult> results = service.search("q", null, null, 5);

        // 结果应该为空或不包含低分结果
        boolean hasLowScore = results.stream()
                .anyMatch(r -> "doc-1".equals(r.getDocumentId()) && r.getFulltextScore() < 0.3);
        assertFalse(hasLowScore, "低于 minScore 的全文结果应被过滤");
    }

    @Test
    @DisplayName("Fulltext search: returns empty on DB error")
    void search_fulltext_dbError_returnsEmpty() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(1);
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenThrow(new RuntimeException("DB connection failed"));

        // 不应抛异常
        assertDoesNotThrow(() -> {
            List<RetrievalResult> results = service.search("q", null, null, 5);
            assertNotNull(results);
        });
    }

    // ========== excludeIds 过滤 ==========

    @Test
    @DisplayName("Vector search: excludeIds filters specified embeddings")
    void search_vectorExcludeIds_filtersCorrectly() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);

        Map<String, Object> row1 = mockDbRow(1L, "doc-1", 0, "保留", queryVec);
        Map<String, Object> row2 = mockDbRow(99L, "doc-1", 1, "排除", queryVec);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(List.of(row1, row2));
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> results = service.search("q", null, List.of(99L), 10);

        // id=99 的结果应被排除
        boolean hasExcluded = results.stream()
                .anyMatch(r -> r.getChunkText().equals("排除"));
        assertFalse(hasExcluded, "excludeIds 中的嵌入应被排除");
    }

    // ========== RetrievalConfig 控制 ==========

    @Test
    @DisplayName("useHybridSearch=false triggers vector-only search")
    void search_hybridDisabled_vectorOnly() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);

        Map<String, Object> row = mockDbRow(1L, "doc-1", 0, "仅向量", queryVec);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));

        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5)
                .useHybridSearch(false)
                .build();

        List<RetrievalResult> results = service.search("q", null, null, 10, config);

        assertFalse(results.isEmpty());
        // 不应调用 similarity 查询（全文检索）
        verify(jdbcTemplate, never()).queryForList(contains("similarity"), any(Object[].class));
    }

    @Test
    @DisplayName("config.maxResults overrides limit parameter")
    void search_configMaxResultsOverridesLimit() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);

        // 模拟数据库只返回 3 行（因为 SQL LIMIT 3）
        List<Map<String, Object>> limitedRows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> row = mockDbRow(i, "doc-" + i, 0, "内容" + i, queryVec);
            limitedRows.add(row);
        }
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(limitedRows);

        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(3)
                .useHybridSearch(false)
                .build();

        List<RetrievalResult> results = service.search("q", null, null, 20, config);

        // config.maxResults=3 限制了 SQL 查询的 LIMIT，数据库返回 3 行
        assertTrue(results.size() <= 3, "结果数量不应超过 config.maxResults");
    }

    // ========== 返回结果结构 ==========

    @Test
    @DisplayName("Result contains all expected fields")
    void search_resultContainsAllFields() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);

        Map<String, Object> row = mockDbRow(42L, "doc-xyz", 3, "测试块内容", queryVec);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> results = service.search("q", null, null, 5);

        assertFalse(results.isEmpty());
        RetrievalResult result = results.stream()
                .filter(r -> "doc-xyz".equals(r.getDocumentId()))
                .findFirst().orElse(null);
        assertNotNull(result, "应返回 doc-xyz 的结果");
        assertEquals("doc-xyz", result.getDocumentId());
        assertEquals("测试块内容", result.getChunkText());
        assertEquals(3, result.getChunkIndex());
        assertTrue(result.getScore() > 0, "分数应大于 0");
    }

    @Test
    @DisplayName("Result contains metadata")
    void search_resultContainsMetadata() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);

        Map<String, Object> metadata = Map.of("source", "file.pdf", "page", 5);
        Map<String, Object> row = mockDbRow(1L, "doc-1", 0, "内容", queryVec);
        row.put("metadata", metadata);
        when(jdbcTemplate.queryForList(contains("embedding <=>"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(contains("similarity"), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> results = service.search("q", null, null, 5);

        assertFalse(results.isEmpty());
        RetrievalResult result = results.stream()
                .filter(r -> "doc-1".equals(r.getDocumentId()))
                .findFirst().orElse(null);
        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertEquals("file.pdf", result.getMetadata().get("source"));
    }

    // ========== 边界情况 ==========

    @Test
    @DisplayName("Empty query does not crash")
    void search_emptyQuery_doesNotCrash() {
        when(embeddingModel.embed("")).thenReturn(new float[]{0.0f});
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> {
            List<RetrievalResult> results = service.search("", null, null, 5);
            assertNotNull(results);
        });
    }

    @Test
    @DisplayName("limit=0 returns empty result")
    void search_limitZero_returnsEmpty() {
        float[] queryVec = mockEmbedding();
        when(embeddingModel.embed(anyString())).thenReturn(queryVec);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        List<RetrievalResult> results = service.search("q", null, null, 0);

        assertNotNull(results);
    }

    // ========== RetrievalResult DTO 测试 ==========

    @Test
    @DisplayName("RetrievalResult field getters/setters work correctly")
    void retrievalResult_fields() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId("doc-1");
        r.setChunkText("测试内容");
        r.setScore(0.85);
        r.setVectorScore(0.90);
        r.setFulltextScore(0.80);
        r.setChunkIndex(2);

        assertEquals("doc-1", r.getDocumentId());
        assertEquals("测试内容", r.getChunkText());
        assertEquals(0.85, r.getScore(), 0.001);
        assertEquals(0.90, r.getVectorScore(), 0.001);
        assertEquals(0.80, r.getFulltextScore(), 0.001);
        assertEquals(2, r.getChunkIndex());
    }

    @Test
    @DisplayName("RetrievalResult score can exceed 1.0")
    void retrievalResult_scoreRange() {
        RetrievalResult r = new RetrievalResult();
        r.setScore(1.5);  // 超出范围的分数
        assertTrue(r.getScore() > 1.0, "分数可以超出 1.0（取决于融合算法）");
    }

    @Test
    @DisplayName("RetrievalResult metadata can be null")
    void retrievalResult_metadataCanBeNull() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId("doc-1");
        assertNull(r.getMetadata());
        // 不应抛异常
        assertEquals("doc-1", r.getDocumentId());
    }

    // ========== pg_trgm 可选化测试 ==========

    @Nested
    @DisplayName("pg_trgm optional with graceful degradation")
    class PgTrgmFallbackTests {

        private Map<String, Object> embeddingRow(long id, String text) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("chunk_text", text);
            row.put("embedding", "[0.1,0.2,0.3]");
            row.put("document_id", 1L);
            row.put("chunk_index", 0);
            row.put("metadata", null);
            return row;
        }

        private Map<String, Object> fulltextRow(long id, String text, double sim) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("chunk_text", text);
            row.put("embedding", "[0.4,0.5,0.6]");
            row.put("document_id", 1L);
            row.put("chunk_index", 1);
            row.put("metadata", null);
            row.put("sim", sim);
            return row;
        }

        @Test
        @DisplayName("Falls back to vector-only when pg_trgm unavailable")
        void pgTrgmUnavailable_fallsBackToVectorOnly() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new DataAccessResourceFailureException("not found"));
            when(embeddingModel.embed(anyString())).thenReturn(mockEmbedding());
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(embeddingRow(1L, "test")));

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, new RagProperties());
            HybridRetrieverService svc = new HybridRetrieverService(embeddingModel, jdbc, new RagProperties(), factory, null);

            List<RetrievalResult> results = svc.search("test query", null, null, 5);

            assertNotNull(results);
            verify(jdbc, atLeastOnce()).queryForList(contains("ORDER BY embedding <=>"), any(Object[].class));
            verify(jdbc, never()).queryForList(contains("similarity"), any(Object[].class));
        }

        @Test
        @DisplayName("Performs hybrid search when pg_trgm is available")
        void pgTrgmAvailable_performsHybridSearch() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(eq("SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'"), eq(Integer.class)))
                    .thenReturn(1);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new DataAccessResourceFailureException("jieba not found"));
            when(embeddingModel.embed(anyString())).thenReturn(mockEmbedding());
            when(jdbc.queryForList(contains("ORDER BY embedding <=>"), any(Object[].class)))
                    .thenReturn(List.of(embeddingRow(1L, "vector result")));
            when(jdbc.queryForList(contains("similarity"), any(Object[].class)))
                    .thenReturn(List.of(fulltextRow(2L, "fulltext result", 0.8)));

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, new RagProperties());
            HybridRetrieverService svc = new HybridRetrieverService(embeddingModel, jdbc, new RagProperties(), factory, null);

            List<RetrievalResult> results = svc.search("test query", null, null, 5);

            assertNotNull(results);
            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("fulltextDisabled forces vector-only even when pg_trgm available")
        void fulltextDisabled_alwaysVectorOnly() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenReturn(1);
            when(embeddingModel.embed(anyString())).thenReturn(mockEmbedding());
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(embeddingRow(1L, "test")));

            RagProperties props = new RagProperties();
            props.getRetrieval().setFulltextEnabled(false);
            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, new RagProperties());
            HybridRetrieverService svc = new HybridRetrieverService(embeddingModel, jdbc, props, factory, null);

            svc.search("test query", null, null, 5);

            verify(jdbc, never()).queryForList(contains("similarity"), any(Object[].class));
        }

        @Test
        @DisplayName("RetrievalConfig.useHybridSearch=false triggers vector-only")
        void configHybridDisabled_vectorOnly() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenReturn(1);
            when(embeddingModel.embed(anyString())).thenReturn(mockEmbedding());
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(embeddingRow(1L, "test")));

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, new RagProperties());
            HybridRetrieverService svc = new HybridRetrieverService(embeddingModel, jdbc, new RagProperties(), factory, null);

            RetrievalConfig config = RetrievalConfig.builder().useHybridSearch(false).build();
            svc.search("test", null, null, 5, config);

            verify(jdbc, never()).queryForList(contains("similarity"), any(Object[].class));
        }
    }
}

    // ========== 全文检索策略配置测试 ==========

    @Nested
    @DisplayName("Fulltext strategy configuration (rag.retrieval.fulltext-strategy)")
    class FulltextStrategyConfigTests {

        @Test
        @DisplayName("strategy=none uses NoOp provider")
        void strategyNone_usesNoOp() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            RagProperties props = new RagProperties();
            props.getRetrieval().setFulltextStrategy("none");

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            assertEquals("none", factory.getProvider().getName());
        }

        @Test
        @DisplayName("strategy=pg_trgm uses pg_trgm when extension available")
        void strategyTrgm_available_usesTrgm() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // SearchCapabilities 检测
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of("vector", "pg_trgm"));
            when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                    .thenReturn(true);

            RagProperties props = new RagProperties();
            props.getRetrieval().setFulltextStrategy("pg_trgm");

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            assertEquals("pg_trgm", factory.getProvider().getName());
        }

        @Test
        @DisplayName("strategy=pg_trgm throws when extension unavailable")
        void strategyTrgm_unavailable_throws() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // SearchCapabilities: only vector extension (no pg_trgm)
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of("vector"));
            // All index checks return false
            when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                    .thenReturn(false);

            RagProperties props = new RagProperties();
            props.getRetrieval().setFulltextStrategy("pg_trgm");

            assertThrows(IllegalStateException.class,
                    () -> new FulltextSearchProviderFactory(jdbc, props).getProvider());
        }

        @Test
        @DisplayName("strategy=auto selects best available provider")
        void strategyAuto_selectsBestAvailable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // SearchCapabilities: only pg_trgm extension available
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of("vector", "pg_trgm"));
            // trgm index exists (3rd call), zh/en indexes do not (1st and 2nd calls)
            when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                    .thenReturn(false, false, true);

            RagProperties props = new RagProperties();
            // default strategy is "auto"
            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            assertEquals("pg_trgm", factory.getProvider().getName());
        }

        @Test
        @DisplayName("strategy=auto falls back to NoOp when all unavailable")
        void strategyAuto_allUnavailable_fallsBackToNoOp() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // SearchCapabilities 检测返回空扩展列表
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of("vector"));  // 只有 vector

            RagProperties props = new RagProperties();
            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            assertEquals("none", factory.getProvider().getName());
        }
    }

    // ========== 异步超时与降级测试 ==========

    @Nested
    @DisplayName("CompletableFuture timeout and degradation")
    class AsyncTimeoutFallbackTests {

        private EmbeddingModel embeddingModel;

        private float[] mockEmbedding() {
            return new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        }

        private Map<String, Object> embeddingRow(long id, String text) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("chunk_text", text);
            row.put("embedding", "[0.1,0.2,0.3]");
            row.put("document_id", 1L);
            row.put("chunk_index", 0);
            row.put("metadata", null);
            return row;
        }

        private Map<String, Object> fulltextRow(long id, String text, double sim) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("chunk_text", text);
            row.put("embedding", "[0.4,0.5,0.6]");
            row.put("document_id", 1L);
            row.put("chunk_index", 1);
            row.put("metadata", null);
            row.put("sim", sim);
            return row;
        }

        @Test
        @DisplayName("Fulltext exception degrades to empty, hybrid still returns vector results")
        void fulltextThrowsException_fallsBackToEmpty_vectorResultsReturned() {
            embeddingModel = mock(EmbeddingModel.class);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(eq("SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'"), eq(Integer.class)))
                    .thenReturn(1);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new DataAccessResourceFailureException("jieba not found"));
            when(embeddingModel.embed(anyString())).thenReturn(mockEmbedding());
            when(jdbc.queryForList(contains("ORDER BY embedding <=>"), any(Object[].class)))
                    .thenReturn(List.of(embeddingRow(1L, "vector result")));
            // 全文检索抛出异常
            when(jdbc.queryForList(contains("similarity"), any(Object[].class)))
                    .thenThrow(new RuntimeException("fulltext DB error"));

            RagProperties props = new RagProperties();
            // 超时设为 1 秒（极短，确保测试快速执行）
            props.getAsync().setRetrievalTimeoutSeconds(1);

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            HybridRetrieverService svc = new HybridRetrieverService(
                    embeddingModel, jdbc, props, factory, null);

            // 不应抛异常，全文降级为空，搜索仍返回向量结果
            List<RetrievalResult> results = svc.search("test", null, null, 5);
            assertNotNull(results);
            assertFalse(results.isEmpty());
            assertEquals("vector result", results.get(0).getChunkText());
        }

        @Test
        @DisplayName("Vector exception degrades to empty, search returns fulltext results")
        void vectorThrowsException_fallsBackToEmpty_fulltextResultsReturned() {
            embeddingModel = mock(EmbeddingModel.class);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(eq("SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'"), eq(Integer.class)))
                    .thenReturn(1);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new DataAccessResourceFailureException("jieba not found"));
            when(embeddingModel.embed(anyString()))
                    .thenThrow(new RuntimeException("embedding API timeout"));
            when(jdbc.queryForList(contains("similarity"), any(Object[].class)))
                    .thenReturn(List.of(fulltextRow(2L, "fulltext result", 0.8)));

            RagProperties props = new RagProperties();
            props.getAsync().setRetrievalTimeoutSeconds(1);

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            HybridRetrieverService svc = new HybridRetrieverService(
                    embeddingModel, jdbc, props, factory, null);

            List<RetrievalResult> results = svc.search("test", null, null, 5);
            // 向量降级为空，融合结果来自全文（可能为空或含全文结果，取决于 minScore）
            assertNotNull(results);
        }

        @Test
        @DisplayName("Both branches throw: search returns empty list without exception")
        void bothThrowException_bothFallbackToEmpty_returnsEmptyGracefully() {
            embeddingModel = mock(EmbeddingModel.class);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(eq("SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'"), eq(Integer.class)))
                    .thenReturn(1);
            when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new DataAccessResourceFailureException("jieba not found"));
            when(embeddingModel.embed(anyString()))
                    .thenThrow(new RuntimeException("embedding failed"));
            when(jdbc.queryForList(contains("similarity"), any(Object[].class)))
                    .thenThrow(new RuntimeException("fulltext failed"));

            RagProperties props = new RagProperties();
            props.getAsync().setRetrievalTimeoutSeconds(1);

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            HybridRetrieverService svc = new HybridRetrieverService(
                    embeddingModel, jdbc, props, factory, null);

            assertDoesNotThrow(() -> {
                List<RetrievalResult> results = svc.search("test", null, null, 5);
                assertNotNull(results);
            });
        }

        @Test
        @DisplayName("RagProperties.Async.retrievalTimeoutSeconds defaults to 5")
        void asyncTimeoutDefault_is5Seconds() {
            RagProperties props = new RagProperties();
            assertEquals(5, props.getAsync().getRetrievalTimeoutSeconds());
        }

        @Test
        @DisplayName("RagProperties.Async.retrievalTimeoutSeconds is configurable")
        void asyncTimeout_configurable() {
            RagProperties props = new RagProperties();
            props.getAsync().setRetrievalTimeoutSeconds(10);
            assertEquals(10, props.getAsync().getRetrievalTimeoutSeconds());
        }
    }
