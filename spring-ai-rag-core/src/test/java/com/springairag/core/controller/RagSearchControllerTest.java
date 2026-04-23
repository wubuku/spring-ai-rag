package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.api.dto.SearchResponse;
import com.springairag.core.repository.RagDocumentRepository;
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
 * RagSearchController Unit Tests
 */
class RagSearchControllerTest {

    private HybridRetrieverService hybridRetriever;
    private RagDocumentRepository documentRepository;
    private RagSearchController controller;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        documentRepository = mock(RagDocumentRepository.class);
        controller = new RagSearchController(hybridRetriever, documentRepository);
    }

    @Test
    @DisplayName("GET search returns response with results/total/query")
    void search_returnsMapWithResultsTotalQuery() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc1");
        r1.setChunkText("测试内容");
        r1.setScore(0.9);

        when(hybridRetriever.search(eq("测试查询"), isNull(), isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of(r1));

        ResponseEntity<?> response = controller.search("测试查询", 10, true, 0.5, 0.5);

        assertEquals(200, response.getStatusCode().value());
        SearchResponse body = (SearchResponse) response.getBody();
        assertNotNull(body);
        assertEquals("测试查询", body.query());
        assertEquals(1, body.total());
        assertEquals(1, body.results().size());
        assertEquals("doc1", body.results().get(0).getDocumentId());
    }

    @Test
    @DisplayName("GET search empty results returns empty list")
    void search_emptyResults_returnsEmptyList() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.search("不存在的查询", 5, true, 0.5, 0.5);

        assertEquals(200, response.getStatusCode().value());
        SearchResponse body = (SearchResponse) response.getBody();
        assertNotNull(body);
        assertEquals(0, body.total());
        assertTrue(body.results().isEmpty());
    }

    @Test
    @DisplayName("GET search with hybrid disabled passes correct config")
    void search_withHybridDisabled_passesConfig() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.search("查询", 5, false, 0.7, 0.3);

        assertEquals(200, response.getStatusCode().value());
        verify(hybridRetriever).search(eq("查询"), isNull(), isNull(), eq(5),
                argThat(config -> !config.isUseHybridSearch()));
    }

    @Test
    @DisplayName("POST search returns result list")
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
    @DisplayName("GET search multiple results order is correct")
    void search_multipleResults_orderCorrect() {
        List<RetrievalResult> results = List.of(
                createResult("doc1", "chunk1", 0.95),
                createResult("doc2", "chunk2", 0.85),
                createResult("doc3", "chunk3", 0.75)
        );

        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(results);

        ResponseEntity<?> response = controller.search("multi query", 10, true, 0.5, 0.5);

        SearchResponse body = (SearchResponse) response.getBody();
        assertNotNull(body);
        assertEquals(3, body.total());
        List<RetrievalResult> resultList = body.results();
        assertEquals("doc1", resultList.get(0).getDocumentId());
        assertEquals("doc3", resultList.get(2).getDocumentId());
    }

    // ========== Weight boundary validation ==========

    @Test
    @DisplayName("vectorWeight > 1.0 returns 400")
    void search_vectorWeightTooHigh_returns400() {
        ResponseEntity<?> response = controller.search("query", 10, true, 1.5, 0.5);
        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("vectorWeight"));
    }

    @Test
    @DisplayName("vectorWeight < 0.0 returns 400")
    void search_vectorWeightTooLow_returns400() {
        ResponseEntity<?> response = controller.search("query", 10, true, -0.1, 0.5);
        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("vectorWeight"));
    }

    @Test
    @DisplayName("fulltextWeight > 1.0 returns 400")
    void search_fulltextWeightTooHigh_returns400() {
        ResponseEntity<?> response = controller.search("query", 10, true, 0.5, 2.0);
        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("fulltextWeight"));
    }

    @Test
    @DisplayName("fulltextWeight < 0.0 returns 400")
    void search_fulltextWeightTooLow_returns400() {
        ResponseEntity<?> response = controller.search("query", 10, true, 0.5, -0.5);
        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("fulltextWeight"));
    }

    @Test
    @DisplayName("Boundary values 0.0 and 1.0 are valid")
    void search_weightBoundaryValues_valid() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        // All weight to vector
        ResponseEntity<?> r1 = controller.search("q", 5, true, 1.0, 0.0);
        assertEquals(200, r1.getStatusCode().value());

        // All weight to fulltext
        ResponseEntity<?> r2 = controller.search("q", 5, true, 0.0, 1.0);
        assertEquals(200, r2.getStatusCode().value());
    }

    // ========== Multi-collection search ==========

    @Test
    @DisplayName("POST with collectionIds resolves to document IDs")
    void searchWithConfig_collectionIds_resolvesToDocumentIds() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(10L, 11L, 12L));
        when(hybridRetriever.search(eq("query"), eq(List.of(10L, 11L, 12L)), isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of(createResult("doc1", "chunk", 0.9)));

        SearchRequest req = new SearchRequest();
        req.setQuery("query");
        req.setCollectionIds(List.of(1L, 2L));

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(documentRepository).findIdsByCollectionIdIn(List.of(1L, 2L));
    }

    @Test
    @DisplayName("POST with both collectionIds and documentIds returns intersection")
    void searchWithConfig_bothCollectionIdsAndDocumentIds_returnsIntersection() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(1L)))
                .thenReturn(List.of(10L, 11L, 12L));
        // Intersection: documentIds [11L, 99L] ∩ collectionDocIds [10L, 11L, 12L] = [11L]
        when(hybridRetriever.search(eq("query"), eq(List.of(11L)), isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of(createResult("doc2", "chunk", 0.8)));

        SearchRequest req = new SearchRequest();
        req.setQuery("query");
        req.setCollectionIds(List.of(1L));
        req.setDocumentIds(List.of(11L, 99L));

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(200, response.getStatusCode().value());
        verify(hybridRetriever).search(eq("query"), eq(List.of(11L)), isNull(), eq(10), any(RetrievalConfig.class));
    }

    @Test
    @DisplayName("POST with collectionIds but no match returns empty list")
    void searchWithConfig_collectionIdsNoMatch_returnsEmpty() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(999L)))
                .thenReturn(List.of()); // no documents in this collection
        when(hybridRetriever.search(eq("query"), eq(List.of()), isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        SearchRequest req = new SearchRequest();
        req.setQuery("query");
        req.setCollectionIds(List.of(999L));

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
