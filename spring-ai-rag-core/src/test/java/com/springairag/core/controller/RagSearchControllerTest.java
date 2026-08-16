package com.springairag.core.controller;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.api.dto.SearchResponse;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionDocumentResolver;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

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
    private ReRankingService reRankingService;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagSearchController controller;
    private RagSearchController productionController;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        documentRepository = mock(RagDocumentRepository.class);
        reRankingService = mock(ReRankingService.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        CollectionDocumentResolver resolver = new CollectionDocumentResolver(documentRepository);
        controller = new RagSearchController(hybridRetriever, resolver, reRankingService);
        productionController = new RagSearchController(
                hybridRetriever, reRankingService, scopeResolver);
        when(reRankingService.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
        verify(reRankingService).rerank(eq("query"), anyList(), eq(5));
    }

    @Test
    @DisplayName("POST search skips rerank when useRerank is false")
    void searchWithConfig_rerankDisabled_skipsRerank() {
        when(hybridRetriever.search(eq("query"), isNull(), isNull(), eq(5), any(RetrievalConfig.class)))
                .thenReturn(List.of(createResult("doc1", "chunk", 0.8)));

        SearchRequest req = new SearchRequest("query");
        req.setConfig(RetrievalConfig.builder()
                .maxResults(5)
                .useRerank(false)
                .build());

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(reRankingService);
    }

    @Test
    @DisplayName("POST search returns reranked order when useRerank is true")
    void searchWithConfig_rerankEnabled_returnsRerankedOrder() {
        List<RetrievalResult> original = List.of(
                createResult("doc1", "first", 0.8),
                createResult("doc2", "second", 0.7));
        List<RetrievalResult> reranked = List.of(original.get(1), original.get(0));
        when(hybridRetriever.search(eq("query"), isNull(), isNull(), eq(2), any(RetrievalConfig.class)))
                .thenReturn(original);
        when(reRankingService.rerank("query", original, 2)).thenReturn(reranked);

        SearchRequest req = new SearchRequest("query");
        req.setConfig(RetrievalConfig.builder()
                .maxResults(2)
                .useRerank(true)
                .build());

        ResponseEntity<List<RetrievalResult>> response = controller.searchWithConfig(req);

        assertEquals(List.of("doc2", "doc1"), response.getBody().stream()
                .map(RetrievalResult::getDocumentId)
                .toList());
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

    @Test
    @DisplayName("limit exceeding 1000 returns 400")
    void search_limitTooHigh_returns400() {
        ResponseEntity<?> response = controller.search("query", 1001, true, 0.5, 0.5);
        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("limit"));
        verifyNoInteractions(hybridRetriever);
    }

    @Test
    @DisplayName("limit of 0 returns 400")
    void search_limitZero_returns400() {
        ResponseEntity<?> response = controller.search("query", 0, true, 0.5, 0.5);
        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("limit"));
        verifyNoInteractions(hybridRetriever);
    }

    @Test
    @DisplayName("limit of 1000 is accepted")
    void search_limit1000Accepted() {
        when(hybridRetriever.search(anyString(), isNull(), isNull(), anyInt(), any(RetrievalConfig.class)))
                .thenReturn(List.of());
        ResponseEntity<?> response = controller.search("query", 1000, true, 0.5, 0.5);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("GET search with blank query returns 400")
    void search_blankQuery_returns400() {
        ResponseEntity<?> response = controller.search("", 10, true, 0.5, 0.5);

        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("blank"));
        verifyNoInteractions(hybridRetriever);
    }

    @Test
    @DisplayName("GET search with whitespace-only query returns 400")
    void search_whitespaceQuery_returns400() {
        ResponseEntity<?> response = controller.search("   ", 10, true, 0.5, 0.5);

        assertEquals(400, response.getStatusCode().value());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.getDetail().contains("blank"));
        verifyNoInteractions(hybridRetriever);
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

    @Test
    @DisplayName("restricted key without collection filter is forced to allowed collections")
    void searchWithConfig_restrictedKeyWithoutFilter_forcesAllowedCollections() {
        when(documentRepository.findIdsByCollectionIdIn(List.of(2L, 4L)))
                .thenReturn(List.of(20L, 40L));
        when(hybridRetriever.search(eq("query"), eq(List.of(20L, 40L)),
                isNull(), eq(10), any(RetrievalConfig.class)))
                .thenReturn(List.of());
        SearchRequest req = new SearchRequest("query");

        controller.searchWithConfig(req, requestForRestrictedKey(2L, 4L));

        assertEquals(List.of(2L, 4L), req.getCollectionIds());
        verify(documentRepository).findIdsByCollectionIdIn(List.of(2L, 4L));
    }

    @Test
    @DisplayName("restricted key cannot search a collection outside its ACL")
    void searchWithConfig_restrictedKeyOutsideAcl_throwsForbidden() {
        SearchRequest req = new SearchRequest("query");
        req.setCollectionIds(List.of(9L));

        assertThrows(SecurityException.class,
                () -> controller.searchWithConfig(
                        req, requestForRestrictedKey(2L, 4L)));
        verifyNoInteractions(hybridRetriever);
    }

    @Test
    @DisplayName("Production GET path passes ANY_COLLECTION scope directly to retriever")
    void productionGet_passesResolvedScopeWithoutDocumentExpansion() {
        RetrievalScope scope = RetrievalScope.anyAssigned(null, null);
        when(scopeResolver.resolve(
                CollectionScopeMode.ANY_COLLECTION,
                null, null, null, null, null))
                .thenReturn(scope);
        when(hybridRetriever.searchInScope(
                eq("query"), same(scope), isNull(), eq(5),
                any(RetrievalConfig.class)))
                .thenReturn(List.of());

        ResponseEntity<?> response = productionController.search(
                "query", 5, true, 0.5, 0.5,
                CollectionScopeMode.ANY_COLLECTION,
                null, null, null);

        assertEquals(200, response.getStatusCode().value());
        verify(hybridRetriever).searchInScope(
                eq("query"), same(scope), isNull(), eq(5),
                any(RetrievalConfig.class));
        verifyNoInteractions(documentRepository);
    }

    @Test
    @DisplayName("Production POST path preserves selected collections and document intersection")
    void productionPost_passesResolvedScopeWithoutDocumentExpansion() {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(2L, 4L), List.of(10L, 11L), null);
        when(scopeResolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("two", "four"),
                List.of(10L, 11L), null, null))
                .thenReturn(scope);
        when(hybridRetriever.searchInScope(
                eq("query"), same(scope), isNull(), eq(8),
                any(RetrievalConfig.class)))
                .thenReturn(List.of());

        SearchRequest request = new SearchRequest("query");
        request.setCollectionScopeMode(
                CollectionScopeMode.SELECTED_COLLECTIONS);
        request.setCollectionKeys(List.of("two", "four"));
        request.setDocumentIds(List.of(10L, 11L));
        request.setConfig(RetrievalConfig.builder()
                .maxResults(8)
                .useRerank(false)
                .build());

        ResponseEntity<List<RetrievalResult>> response =
                productionController.searchWithConfig(request, null);

        assertEquals(200, response.getStatusCode().value());
        verify(hybridRetriever).searchInScope(
                eq("query"), same(scope), isNull(), eq(8),
                any(RetrievalConfig.class));
        verifyNoInteractions(documentRepository);
    }

    private MockHttpServletRequest requestForRestrictedKey(Long... ids) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(String.join(",",
                java.util.Arrays.stream(ids).map(String::valueOf).toList()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key);
        return request;
    }

    private RetrievalResult createResult(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
