package com.springairag.core.controller;

import com.springairag.core.metrics.CacheMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagMetricsController 测试")
class RagMetricsControllerTest {

    @Mock
    private RagMetricsService metricsService;

    @Mock
    private CacheMetricsService cacheMetricsService;

    private RagMetricsController controller;

    @BeforeEach
    void setUp() {
        controller = new RagMetricsController(metricsService, cacheMetricsService);
    }

    @Test
    @DisplayName("GET /metrics/rag 返回 RAG 核心指标")
    void getRagMetrics_returnsCoreMetrics() {
        when(metricsService.getTotalRequests()).thenReturn(100L);
        when(metricsService.getSuccessfulRequests()).thenReturn(95L);
        when(metricsService.getFailedRequests()).thenReturn(5L);
        when(metricsService.getSuccessRate()).thenReturn(95.0);
        when(metricsService.getTotalRetrievalResults()).thenReturn(500L);
        when(metricsService.getTotalLlmTokens()).thenReturn(10_000L);

        ResponseEntity<Map<String, Object>> response = controller.getRagMetrics();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(100L, response.getBody().get("totalRequests"));
        assertEquals(95L, response.getBody().get("successfulRequests"));
        assertEquals(5L, response.getBody().get("failedRequests"));
        assertEquals("95.00%", response.getBody().get("successRate"));
        assertEquals(500L, response.getBody().get("retrievalResultsTotal"));
        assertEquals(10_000L, response.getBody().get("llmTokensTotal"));

        verify(metricsService).getTotalRequests();
        verify(metricsService).getSuccessfulRequests();
        verify(metricsService).getFailedRequests();
        verify(metricsService).getSuccessRate();
        verify(metricsService).getTotalRetrievalResults();
        verify(metricsService).getTotalLlmTokens();
    }

    @Test
    @DisplayName("GET /metrics/overview 返回 RAG + 缓存双视图")
    void getOverview_returnsDualView() {
        when(metricsService.getTotalRequests()).thenReturn(50L);
        when(metricsService.getSuccessfulRequests()).thenReturn(48L);
        when(metricsService.getFailedRequests()).thenReturn(2L);
        when(metricsService.getSuccessRate()).thenReturn(96.0);
        when(metricsService.getTotalRetrievalResults()).thenReturn(250L);
        when(metricsService.getTotalLlmTokens()).thenReturn(5_000L);
        when(cacheMetricsService.getStats()).thenReturn(Map.of(
                "hits", 80L,
                "misses", 20L,
                "hitRate", "80.00%"
        ));

        ResponseEntity<Map<String, Object>> response = controller.getOverview();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("rag"));
        assertNotNull(response.getBody().get("cache"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ragMetrics = (Map<String, Object>) response.getBody().get("rag");
        assertEquals(50L, ragMetrics.get("totalRequests"));
        assertEquals(48L, ragMetrics.get("successfulRequests"));
        assertEquals("96.00%", ragMetrics.get("successRate"));

        @SuppressWarnings("unchecked")
        Map<String, Object> cacheStats = (Map<String, Object>) response.getBody().get("cache");
        assertEquals(80L, cacheStats.get("hits"));
        assertEquals("80.00%", cacheStats.get("hitRate"));

        verify(metricsService).getTotalRequests();
        verify(cacheMetricsService).getStats();
    }

    @Test
    @DisplayName("成功率格式化保留两位小数")
    void getRagMetrics_formatsSuccessRateWithTwoDecimals() {
        when(metricsService.getTotalRequests()).thenReturn(3L);
        when(metricsService.getSuccessfulRequests()).thenReturn(1L);
        when(metricsService.getFailedRequests()).thenReturn(2L);
        when(metricsService.getSuccessRate()).thenReturn(33.3333333);
        when(metricsService.getTotalRetrievalResults()).thenReturn(0L);
        when(metricsService.getTotalLlmTokens()).thenReturn(0L);

        ResponseEntity<Map<String, Object>> response = controller.getRagMetrics();

        assertEquals("33.33%", response.getBody().get("successRate"));
    }
}
