package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索追踪注入（traced）：诊断开启时创建会话、绑定范围、回写
 * X-Trace-Id 响应头；诊断抛异常时降级为无追踪的正常响应。
 */
@ExtendWith(MockitoExtension.class)
class RagSearchTracingTest {

    @Mock HybridRetrieverService hybridRetriever;
    @Mock CollectionRetrievalScopeResolver scopeResolver;
    @Mock ReRankingService reRankingService;
    @Mock RetrievalDiagnosticsService diagnosticsService;

    private RagSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new RagSearchController(
                hybridRetriever, reRankingService, scopeResolver);
        controller.setDiagnosticsService(diagnosticsService);
        lenient().when(diagnosticsService.isEnabled()).thenReturn(true);
        // createSession 返回真实会话，保证 traceId 可写入响应头。
        lenient().when(diagnosticsService.createSession(any(), anyString(), any()))
                .thenReturn(new com.springairag.core.diagnostics.RetrievalTraceSession(
                        com.springairag.core.chat.ChatPrincipal.local(),
                        com.springairag.core.retrieval.RetrievalTraceHeaders.OPERATION_SEARCH,
                        null));
        lenient().when(hybridRetriever.searchInScopeDetailed(
                anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(RetrievalConfig.class), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of()));
    }

    private ResponseEntity<?> performSearch() {
        RetrievalScope scope = RetrievalScope.anyAssigned(null, null);
        when(scopeResolver.resolve(
                CollectionScopeMode.ANY_COLLECTION,
                null, null, null, null, null))
                .thenReturn(scope);

        return controller.search(
                "query", 5, true, 0.5, 0.5,
                CollectionScopeMode.ANY_COLLECTION,
                null, null,
                new org.springframework.mock.web.MockHttpServletRequest());
    }

    @Test
    void searchWithDiagnosticsAttachesTraceIdHeader() {
        ResponseEntity<?> response = performSearch();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getFirst(
                com.springairag.core.retrieval.RetrievalTraceHeaders.TRACE_ID));
        verify(diagnosticsService).persistSearch(
                any(), any(), any(), any());
    }

    @Test
    void searchWithoutTraceHeaderWhenDiagnosticsDisabled() {
        when(diagnosticsService.isEnabled()).thenReturn(false);

        ResponseEntity<?> response = performSearch();

        assertEquals(200, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst(
                com.springairag.core.retrieval.RetrievalTraceHeaders.TRACE_ID));
    }
}
