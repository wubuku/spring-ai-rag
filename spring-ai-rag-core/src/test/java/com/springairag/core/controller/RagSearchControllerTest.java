package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.core.retrieval.HybridRetrieverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagSearchController 单元测试
 */
class RagSearchControllerTest {

    private HybridRetrieverService hybridRetriever;
    private RagSearchController controller;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        controller = new RagSearchController(hybridRetriever);
    }

    @Test
    @DisplayName("GET 检索返回 Map 包含 results/total/query")
    void search_returnsMapWithResultsTotalQuery() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc1");
        r1.setChunkText("测试内容");
        r1.setScore(0.9);

        when(hybridRetriever.search(eq("测试查询"), isNull(), isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of(r1));

        ResponseEntity<Map<String, Object>> response = controller.search("测试查询", 10, true, 0.5, 0.5);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("测试查询", body.get("query"));
        assertEquals(1, body.get("total"));
        @SuppressWarnings("unchecked")
        List<RetrievalResult> results = (List<RetrievalResult>) body.get("results");
        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).getDocumentId());
    }

    @Test
    @DisplayName("GET 检索空结果返回空列表")
    void search_emptyResults_returnsEmptyList() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.search("不存在的查询", 5, true, 0.5, 0.5);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("total"));
        @SuppressWarnings("unchecked")
        List<RetrievalResult> results = (List<RetrievalResult>) body.get("results");
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("GET 检索关闭混合检索时传递正确配置")
    void search_withHybridDisabled_passesConfig() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.search("查询", 5, false, 0.7, 0.3);

        assertEquals(200, response.getStatusCode().value());
        verify(hybridRetriever).search(eq("查询"), isNull(), isNull(), eq(5),
                argThat(config -> !config.isUseHybridSearch()));
    }

    @Test
    @DisplayName("POST 检索返回结果列表")
    void searchWithConfig_returnsResults() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc1");
        r1.setChunkText("chunk1");
        r1.setScore(0.8);

        when(hybridRetriever.search(eq("query"), eq(List.of(1L, 2L)), isNull(), eq(5), any(RetrievalConfig.class)))
                .thenReturn(List.of(r1));

        SearchRequest req = new SearchRequest();
        req.setQuery("query");
        req.setDocumentIds(List.of(1L, 2L));
        RetrievalConfig config = RetrievalConfig.builder().maxResults(5).build();
        req.setConfig(config);

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("doc1", response.getBody().get(0).getDocumentId());
    }

    @Test
    @DisplayName("GET 检索多条结果顺序正确")
    void search_multipleResults_orderCorrect() {
        List<RetrievalResult> results = List.of(
                createResult("doc1", "chunk1", 0.95),
                createResult("doc2", "chunk2", 0.85),
                createResult("doc3", "chunk3", 0.75)
        );

        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(results);

        ResponseEntity<Map<String, Object>> response = controller.search("multi query", 10, true, 0.5, 0.5);

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(3, body.get("total"));
        @SuppressWarnings("unchecked")
        List<RetrievalResult> resultList = (List<RetrievalResult>) body.get("results");
        assertEquals("doc1", resultList.get(0).getDocumentId());
        assertEquals("doc3", resultList.get(2).getDocumentId());
    }

    // ========== 权重边界验证 ==========

    @Test
    @DisplayName("vectorWeight > 1.0 返回 400")
    void search_vectorWeightTooHigh_returns400() {
        ResponseEntity<Map<String, Object>> response = controller.search("query", 10, true, 1.5, 0.5);
        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("vectorWeight must be between 0.0 and 1.0", body.get("error"));
        assertEquals(1.5, body.get("received"));
    }

    @Test
    @DisplayName("vectorWeight < 0.0 返回 400")
    void search_vectorWeightTooLow_returns400() {
        ResponseEntity<Map<String, Object>> response = controller.search("query", 10, true, -0.1, 0.5);
        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("vectorWeight must be between 0.0 and 1.0", body.get("error"));
        assertEquals(-0.1, body.get("received"));
    }

    @Test
    @DisplayName("fulltextWeight > 1.0 返回 400")
    void search_fulltextWeightTooHigh_returns400() {
        ResponseEntity<Map<String, Object>> response = controller.search("query", 10, true, 0.5, 2.0);
        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("fulltextWeight must be between 0.0 and 1.0", body.get("error"));
        assertEquals(2.0, body.get("received"));
    }

    @Test
    @DisplayName("fulltextWeight < 0.0 返回 400")
    void search_fulltextWeightTooLow_returns400() {
        ResponseEntity<Map<String, Object>> response = controller.search("query", 10, true, 0.5, -0.5);
        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("fulltextWeight must be between 0.0 and 1.0", body.get("error"));
        assertEquals(-0.5, body.get("received"));
    }

    @Test
    @DisplayName("边界值 0.0 和 1.0 均合法")
    void search_weightBoundaryValues_valid() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        // 全部由向量承担
        ResponseEntity<Map<String, Object>> r1 = controller.search("q", 5, true, 1.0, 0.0);
        assertEquals(200, r1.getStatusCode().value());

        // 全部由全文承担
        ResponseEntity<Map<String, Object>> r2 = controller.search("q", 5, true, 0.0, 1.0);
        assertEquals(200, r2.getStatusCode().value());
    }

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
