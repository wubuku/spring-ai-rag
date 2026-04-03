package com.springairag.core.controller;

import com.springairag.api.dto.RagMetricsSummary;
import com.springairag.core.metrics.RagMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagMetricsController unit test — pure Mockito, no Spring context
 */
@ExtendWith(MockitoExtension.class)
class RagMetricsControllerTest {

    @Mock
    private RagMetricsService metricsService;

    private RagMetricsController controller;

    @BeforeEach
    void setUp() {
        controller = new RagMetricsController(metricsService);
    }

    @Test
    void getMetrics_returnsAllFields() {
        when(metricsService.getTotalRequests()).thenReturn(100L);
        when(metricsService.getSuccessfulRequests()).thenReturn(95L);
        when(metricsService.getFailedRequests()).thenReturn(5L);
        when(metricsService.getSuccessRate()).thenReturn(95.0);
        when(metricsService.getTotalRetrievalResults()).thenReturn(1500L);
        when(metricsService.getTotalLlmTokens()).thenReturn(75000L);

        RagMetricsSummary result = controller.getMetrics();

        assertNotNull(result);
        assertEquals(100L, result.totalRequests());
        assertEquals(95L, result.successfulRequests());
        assertEquals(5L, result.failedRequests());
        assertEquals(95.0, result.successRate());
        assertEquals(1500L, result.totalRetrievalResults());
        assertEquals(75000L, result.totalLlmTokens());
        assertNotNull(result.timestamp());
    }

    @Test
    void getMetrics_zeroRequests_returnsDefaultValues() {
        when(metricsService.getTotalRequests()).thenReturn(0L);
        when(metricsService.getSuccessfulRequests()).thenReturn(0L);
        when(metricsService.getFailedRequests()).thenReturn(0L);
        when(metricsService.getSuccessRate()).thenReturn(100.0);
        when(metricsService.getTotalRetrievalResults()).thenReturn(0L);
        when(metricsService.getTotalLlmTokens()).thenReturn(0L);

        RagMetricsSummary result = controller.getMetrics();

        assertNotNull(result);
        assertEquals(0L, result.totalRequests());
        assertEquals(100.0, result.successRate());
    }
}
