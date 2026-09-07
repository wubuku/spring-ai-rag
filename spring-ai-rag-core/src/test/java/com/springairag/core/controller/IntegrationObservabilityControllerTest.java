package com.springairag.core.controller;

import com.springairag.api.dto.IntegrationObservabilityResponse;
import com.springairag.core.observability.IntegrationObservabilityQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 集成可观测性查询端点：参数透传、no-store 缓存头与响应包装。 */
class IntegrationObservabilityControllerTest {

    private IntegrationObservabilityQueryService queryService;
    private IntegrationObservabilityController controller;
    private IntegrationObservabilityResponse response;

    @BeforeEach
    void setUp() {
        queryService = mock(IntegrationObservabilityQueryService.class);
        controller = new IntegrationObservabilityController(queryService);
        response = mock(IntegrationObservabilityResponse.class);
    }

    @Test
    void queryForwardsEveryParameterInTheDocumentedOrder() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/rag/integration-observability");
        when(queryService.query(
                request, "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z",
                "DAY", "PDF_IMPORT", "kb", "db:key-1"))
                .thenReturn(response);

        ResponseEntity<IntegrationObservabilityResponse> result = controller.query(
                "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z",
                "DAY", "PDF_IMPORT", "kb", "db:key-1",
                request);

        assertEquals(200, result.getStatusCode().value());
        assertSame(response, result.getBody());
        // 敏感的运营聚合禁止缓存。
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(queryService).query(
                request, "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z",
                "DAY", "PDF_IMPORT", "kb", "db:key-1");
    }

    @Test
    void queryForwardsNullDefaultsAndStillAppliesNoStore() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/rag/integration-observability");
        when(queryService.query(
                request, null, null, null, null, null, null))
                .thenReturn(response);

        ResponseEntity<IntegrationObservabilityResponse> result =
                controller.query(null, null, null, null, null, null, request);

        assertSame(response, result.getBody());
        verify(queryService).query(
                request, null, null, null, null, null, null);
    }
}
