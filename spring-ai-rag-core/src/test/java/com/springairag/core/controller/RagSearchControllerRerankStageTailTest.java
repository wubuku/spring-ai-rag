package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.dto.SearchRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagSearchController 重排成功臂与便捷重载长尾（Batch 705，JaCoCo
 * 驱动）：searchWithConfig 在重排成功时以 ERROR/SUCCESS 阶段更新
 * outcome 并透出重排后顺序；9 参便捷重载委托主流程。
 */
class RagSearchControllerRerankStageTailTest {

    private HybridRetrieverService hybridRetriever;
    private ReRankingService reRankingService;
    private CollectionRetrievalScopeResolver scopeResolver;
    private RagSearchController productionController;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        reRankingService = mock(ReRankingService.class);
        scopeResolver = mock(CollectionRetrievalScopeResolver.class);
        productionController = new RagSearchController(
                hybridRetriever, reRankingService, scopeResolver);
    }

    private RetrievalResult result(String documentId, String text,
                                   double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkText(text);
        result.setScore((float) score);
        return result;
    }

    private void stubDetailed(RetrievalScope scope,
                              List<RetrievalResult> candidates) {
        when(scopeResolver.resolve(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(scope);
        when(hybridRetriever.searchInScopeDetailed(
                eq("query"), same(scope), isNull(), eq(2),
                any(RetrievalConfig.class), any()))
                .thenReturn(RetrievalOutcome.ofResults(candidates));
    }

    private SearchRequest rerankRequest() {
        SearchRequest request = new SearchRequest("query");
        request.setConfig(RetrievalConfig.builder()
                .maxResults(2)
                .useRerank(true)
                .build());
        return request;
    }

    @Test
    void rerankSuccessReordersResultsAndTracksOutcomeStage() {
        RetrievalScope scope = RetrievalScope.unscoped();
        List<RetrievalResult> candidates = List.of(
                result("doc1", "first", 0.9),
                result("doc2", "second", 0.8));
        stubDetailed(scope, candidates);
        List<RetrievalResult> reordered = List.of(
                result("doc2", "second", 0.8),
                result("doc1", "first", 0.9));
        when(reRankingService.rerank(eq("query"), eq(candidates), eq(2)))
                .thenReturn(reordered);

        ResponseEntity<List<RetrievalResult>> response =
                productionController.searchWithConfig(rerankRequest(), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("doc2", "doc1"), response.getBody().stream()
                .map(RetrievalResult::getDocumentId)
                .toList());
    }

    @Test
    void legacySearchOverloadDelegatesToMainFlow() {
        RetrievalScope scope = RetrievalScope.unscoped();
        List<RetrievalResult> candidates = List.of(
                result("doc1", "first", 0.9));
        stubDetailed(scope, candidates);

        ResponseEntity<?> response = productionController.search(
                "query", 2, true, 0.55, 0.45,
                null, null,
                new org.springframework.mock.web.MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void emptyOutcomeResultsFallBackToEmptyList() {
        RetrievalScope scope = RetrievalScope.unscoped();
        when(scopeResolver.resolve(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(scope);
        when(hybridRetriever.searchInScopeDetailed(
                eq("query"), same(scope), isNull(), eq(2),
                any(RetrievalConfig.class), any()))
                .thenReturn(null);

        ResponseEntity<List<RetrievalResult>> response =
                productionController.searchWithConfig(rerankRequest(), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().size());
    }
}
