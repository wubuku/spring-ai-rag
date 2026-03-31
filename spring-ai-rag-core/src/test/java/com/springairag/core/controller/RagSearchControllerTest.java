package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import org.junit.jupiter.api.BeforeEach;
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
    void search_returnsResults() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc1");
        r1.setChunkText("测试内容");
        r1.setScore(0.9);

        when(hybridRetriever.search(eq("测试查询"), isNull(), isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of(r1));

        ResponseEntity<List<RetrievalResult>> response = controller.search("测试查询", 10, true, 0.5, 0.5);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("doc1", response.getBody().get(0).getDocumentId());
    }

    @Test
    void search_emptyResults() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<List<RetrievalResult>> response = controller.search("不存在的查询", 5, true, 0.5, 0.5);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void search_withHybridDisabled() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<List<RetrievalResult>> response = controller.search("查询", 5, false, 0.7, 0.3);

        assertEquals(200, response.getStatusCode().value());
        verify(hybridRetriever).search(eq("查询"), isNull(), isNull(), eq(5),
                argThat(config -> !config.isUseHybridSearch()));
    }

    @Test
    void searchWithConfig_passesConfig() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc1");
        r1.setChunkText("chunk1");
        r1.setScore(0.8);

        when(hybridRetriever.search(eq("query"), eq(List.of(1L, 2L)), isNull(), eq(5), any(RetrievalConfig.class)))
                .thenReturn(List.of(r1));

        RagSearchController.SearchRequest req = new RagSearchController.SearchRequest();
        req.setQuery("query");
        req.setDocumentIds(List.of(1L, 2L));
        RetrievalConfig config = RetrievalConfig.builder().maxResults(5).build();
        req.setConfig(config);

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void search_multipleResults() {
        List<RetrievalResult> results = List.of(
                createResult("doc1", "chunk1", 0.95),
                createResult("doc2", "chunk2", 0.85),
                createResult("doc3", "chunk3", 0.75)
        );

        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(results);

        ResponseEntity<List<RetrievalResult>> response = controller.search("multi query", 10, true, 0.5, 0.5);

        assertEquals(3, response.getBody().size());
        assertEquals("doc1", response.getBody().get(0).getDocumentId());
        assertEquals("doc3", response.getBody().get(2).getDocumentId());
    }

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
