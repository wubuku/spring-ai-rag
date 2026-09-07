package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalTraceDetailResponse;
import com.springairag.api.dto.RetrievalTracePageResponse;
import com.springairag.core.diagnostics.RetrievalDiagnosticsService;
import com.springairag.core.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 检索诊断只读 API：过滤器透传、分页绑定与认证 principal 推导。 */
class RetrievalTraceControllerTest {

    private RetrievalDiagnosticsService diagnosticsService;
    private RetrievalTraceController controller;
    private RetrievalTracePageResponse page;
    private RetrievalTraceDetailResponse detail;

    @BeforeEach
    void setUp() {
        diagnosticsService = mock(RetrievalDiagnosticsService.class);
        controller = new RetrievalTraceController(diagnosticsService);
        page = mock(RetrievalTracePageResponse.class);
        detail = mock(RetrievalTraceDetailResponse.class);
    }

    private MockHttpServletRequest localRequest() {
        return new MockHttpServletRequest("GET", "/rag/retrieval-traces");
    }

    @Test
    void listBindsDefaultsAndForwardsToLocalPrincipal() {
        when(diagnosticsService.list(
                any(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(0), eq(20)))
                .thenReturn(page);

        RetrievalTracePageResponse response = controller.list(
                0, 20, null, null, null, null, null, localRequest());

        assertSame(page, response);
        ArgumentCaptor<Object> principal = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(diagnosticsService).list(
                any(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(0), eq(20));
    }

    @Test
    void listForwardsEveryFilterValueToTheDiagnosticsService() {
        when(diagnosticsService.list(
                any(), eq("RETRY"), eq("EMPTY_RESULTS"), eq("NO_SOURCES"),
                eq("session-9"), eq("MISSING"), eq(2), eq(50)))
                .thenReturn(page);

        RetrievalTracePageResponse response = controller.list(
                2, 50, "RETRY", "EMPTY_RESULTS", "NO_SOURCES",
                "session-9", "MISSING", localRequest());

        assertSame(page, response);
    }

    @Test
    void listDerivesADatabasePrincipalFromAuthenticatedAttributes() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/rag/retrieval-traces");
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "key-42");
        when(diagnosticsService.list(
                any(), isNull(), isNull(), isNull(), isNull(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(page);

        controller.list(0, 20, null, null, null, null, null, request);

        org.mockito.Mockito.verify(diagnosticsService).list(
                org.mockito.ArgumentMatchers.argThat(p ->
                        "db:key-42".equals(p.id())),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(0), eq(20));
    }

    @Test
    void getForwardsTheTraceIdAndPrincipal() {
        UUID traceId = UUID.randomUUID();
        when(diagnosticsService.get(any(), eq(traceId))).thenReturn(detail);

        RetrievalTraceDetailResponse response =
                controller.get(traceId, localRequest());

        assertSame(detail, response);
    }
}
