package com.springairag.core.controller;

import com.springairag.api.dto.SearchResponse;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.service.CollectionDocumentResolver;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 直搜控制器 legacy 路径残余（Batch 371）：集合过滤解析为空时
 * 直接空响应、8 参重载委派、detailed 结果缺失回退空列表。
 */
class RagSearchControllerLegacyPathTest {

    private HybridRetrieverService hybridRetriever;
    private RagDocumentRepository documentRepository;
    private ReRankingService reRankingService;
    private RagSearchController controller;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetrieverService.class);
        documentRepository = mock(RagDocumentRepository.class);
        reRankingService = mock(ReRankingService.class);
        CollectionDocumentResolver resolver =
                new CollectionDocumentResolver(documentRepository);
        controller = new RagSearchController(
                hybridRetriever, resolver, reRankingService);
    }

    @Test
    void collectionFilterWithNoResolvedDocumentsReturnsEmptyResponse() {
        // 仓储无匹配 → 解析文档 id 为空 → 集合过滤下直接空响应。
        when(documentRepository.findIdsByCollectionIdIn(List.of(7L)))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.search(
                "query", 5, true, 0.5, 0.5,
                null, List.of(7L), null,
                new MockHttpServletRequest());

        SearchResponse body = (SearchResponse) response.getBody();
        assertInstanceOf(SearchResponse.class, body);
        assertEquals(0, body.total());
    }

    @Test
    void eightArgOverloadDelegatesAndReturnsResults() {
        when(hybridRetriever.search(eq("query"), isNull(), isNull(),
                eq(5), any(com.springairag.api.dto.RetrievalConfig.class)))
                .thenReturn(List.of(new com.springairag.api.dto.RetrievalResult()));

        ResponseEntity<?> response = controller.search(
                "query", 5, true, 0.5, 0.5,
                null, null, null,
                new MockHttpServletRequest());

        SearchResponse body = assertInstanceOf(SearchResponse.class,
                response.getBody());
        assertEquals(1, body.total());
    }
}
