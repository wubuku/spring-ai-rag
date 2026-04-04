package com.springairag.core.controller;

import com.springairag.api.dto.ModelMetricsResponse;
import com.springairag.api.dto.RagMetricsSummary;
import com.springairag.core.config.ChatModelRouter;
import com.springairag.core.config.ModelRegistry;
import com.springairag.core.metrics.ModelMetricsService;
import com.springairag.core.metrics.RagMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagMetricsController unit test — pure Mockito, no Spring context
 */
@ExtendWith(MockitoExtension.class)
class RagMetricsControllerTest {

    @Mock
    private RagMetricsService metricsService;

    @Mock
    private ModelMetricsService modelMetricsService;

    @Mock
    private ModelRegistry modelRegistry;

    @Mock
    private ChatModelRouter modelRouter;

    private RagMetricsController controller;

    @BeforeEach
    void setUp() {
        controller = new RagMetricsController(
                metricsService, modelMetricsService, modelRegistry, modelRouter);
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

    @Test
    void getModelMetrics_multiModelEnabled_returnsProvidersWithStats() {
        when(modelRouter.isMultiModelEnabled()).thenReturn(true);
        when(modelRouter.getAvailableProviders()).thenReturn(List.of("openai", "minimax"));

        when(modelMetricsService.getCallCount("openai")).thenReturn(50L);
        when(modelMetricsService.getErrorCount("openai")).thenReturn(2L);
        when(modelMetricsService.getErrorRate("openai")).thenReturn(4.0);
        when(modelRegistry.getDisplayName("openai")).thenReturn("DeepSeek V3");

        when(modelMetricsService.getCallCount("minimax")).thenReturn(30L);
        when(modelMetricsService.getErrorCount("minimax")).thenReturn(1L);
        when(modelMetricsService.getErrorRate("minimax")).thenReturn(3.23);
        when(modelRegistry.getDisplayName("minimax")).thenReturn("MiniMax M2");

        ModelMetricsResponse result = controller.getModelMetrics();

        assertNotNull(result);
        assertEquals(true, result.multiModelEnabled());

        List<ModelMetricsResponse.ModelMetric> models = result.models();
        assertEquals(2, models.size());

        ModelMetricsResponse.ModelMetric openaiStats = models.get(0);
        assertEquals("openai", openaiStats.provider());
        assertEquals(50L, openaiStats.calls());
        assertEquals(2L, openaiStats.errors());
        assertEquals(4.0, openaiStats.errorRate());
        assertEquals("DeepSeek V3", openaiStats.displayName());

        ModelMetricsResponse.ModelMetric minimaxStats = models.get(1);
        assertEquals("minimax", minimaxStats.provider());
        assertEquals(30L, minimaxStats.calls());
        assertEquals(1L, minimaxStats.errors());
    }

    @Test
    void getModelMetrics_emptyProviders_returnsEmptyList() {
        when(modelRouter.isMultiModelEnabled()).thenReturn(false);
        when(modelRouter.getAvailableProviders()).thenReturn(Collections.emptyList());

        ModelMetricsResponse result = controller.getModelMetrics();

        assertNotNull(result);
        assertEquals(false, result.multiModelEnabled());
        assertTrue(result.models().isEmpty());
    }

    @Test
    void getModelMetrics_singleProvider_mapsAllFields() {
        when(modelRouter.isMultiModelEnabled()).thenReturn(true);
        when(modelRouter.getAvailableProviders()).thenReturn(List.of("anthropic"));

        when(modelMetricsService.getCallCount("anthropic")).thenReturn(0L);
        when(modelMetricsService.getErrorCount("anthropic")).thenReturn(0L);
        when(modelMetricsService.getErrorRate("anthropic")).thenReturn(0.0);
        when(modelRegistry.getDisplayName("anthropic")).thenReturn("Claude 3.5 Sonnet");

        ModelMetricsResponse result = controller.getModelMetrics();

        assertNotNull(result);
        assertEquals(true, result.multiModelEnabled());

        List<ModelMetricsResponse.ModelMetric> models = result.models();
        assertEquals(1, models.size());

        ModelMetricsResponse.ModelMetric stats = models.get(0);
        assertEquals("anthropic", stats.provider());
        assertEquals(0L, stats.calls());
        assertEquals(0L, stats.errors());
        assertEquals(0.0, stats.errorRate());
        assertEquals("Claude 3.5 Sonnet", stats.displayName());
    }
}
