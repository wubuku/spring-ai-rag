package com.springairag.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagMetricsService.
 */
@DisplayName("RagMetricsService Tests")
class RagMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private RagMetricsService ragMetricsService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ragMetricsService = new RagMetricsService(meterRegistry);
    }

    @Test
    @DisplayName("Initial total requests should be zero")
    void getTotalRequests_initiallyZero() {
        assertEquals(0, ragMetricsService.getTotalRequests());
    }

    @Test
    @DisplayName("Initial success requests should be zero")
    void getSuccessfulRequests_initiallyZero() {
        assertEquals(0, ragMetricsService.getSuccessfulRequests());
    }

    @Test
    @DisplayName("Initial failed requests should be zero")
    void getFailedRequests_initiallyZero() {
        assertEquals(0, ragMetricsService.getFailedRequests());
    }

    @Test
    @DisplayName("Initial retrieval results should be zero")
    void getTotalRetrievalResults_initiallyZero() {
        assertEquals(0, ragMetricsService.getTotalRetrievalResults());
    }

    @Test
    @DisplayName("Initial LLM tokens should be zero")
    void getTotalLlmTokens_initiallyZero() {
        assertEquals(0, ragMetricsService.getTotalLlmTokens());
    }

    @Test
    @DisplayName("getSuccessRate should return 100 when no requests")
    void getSuccessRate_noRequests_returns100() {
        assertEquals(100.0, ragMetricsService.getSuccessRate());
    }

    @Test
    @DisplayName("recordSuccess should increment counters correctly")
    void recordSuccess_incrementsCounters() {
        ragMetricsService.recordSuccess(150, 5);

        assertEquals(1, ragMetricsService.getTotalRequests());
        assertEquals(1, ragMetricsService.getSuccessfulRequests());
        assertEquals(0, ragMetricsService.getFailedRequests());
        assertEquals(5, ragMetricsService.getTotalRetrievalResults());
    }

    @Test
    @DisplayName("recordSuccess should accumulate retrieval results")
    void recordSuccess_accumulatesRetrievalResults() {
        ragMetricsService.recordSuccess(100, 3);
        ragMetricsService.recordSuccess(200, 7);
        ragMetricsService.recordSuccess(50, 2);

        assertEquals(12, ragMetricsService.getTotalRetrievalResults());
    }

    @Test
    @DisplayName("recordFailure should increment counters correctly")
    void recordFailure_incrementsCounters() {
        ragMetricsService.recordFailure(300);

        assertEquals(1, ragMetricsService.getTotalRequests());
        assertEquals(0, ragMetricsService.getSuccessfulRequests());
        assertEquals(1, ragMetricsService.getFailedRequests());
    }

    @Test
    @DisplayName("recordFailure should not affect retrieval results")
    void recordFailure_doesNotAffectRetrievalResults() {
        ragMetricsService.recordSuccess(100, 5);
        ragMetricsService.recordFailure(300);

        assertEquals(5, ragMetricsService.getTotalRetrievalResults());
    }

    @Test
    @DisplayName("getSuccessRate should return correct percentage")
    void getSuccessRate_withData_returnsCorrectPercentage() {
        ragMetricsService.recordSuccess(100, 5);
        ragMetricsService.recordSuccess(200, 3);
        ragMetricsService.recordFailure(300);

        // 2 successes out of 3 total = 66.67%
        assertEquals(66.67, ragMetricsService.getSuccessRate(), 0.5);
    }

    @Test
    @DisplayName("recordLlmTokens should accumulate tokens")
    void recordLlmTokens_accumulates() {
        ragMetricsService.recordLlmTokens(1000);
        ragMetricsService.recordLlmTokens(500);

        assertEquals(1500, ragMetricsService.getTotalLlmTokens());
    }

    @Test
    @DisplayName("Multiple success and failure calls should track correctly")
    void mixedCalls_tracksCorrectly() {
        ragMetricsService.recordSuccess(100, 5);
        ragMetricsService.recordSuccess(200, 3);
        ragMetricsService.recordFailure(50);
        ragMetricsService.recordSuccess(150, 7);
        ragMetricsService.recordFailure(80);

        assertEquals(5, ragMetricsService.getTotalRequests());
        assertEquals(3, ragMetricsService.getSuccessfulRequests());
        assertEquals(2, ragMetricsService.getFailedRequests());
        assertEquals(15, ragMetricsService.getTotalRetrievalResults());
        assertEquals(60.0, ragMetricsService.getSuccessRate(), 0.1);
    }

    @Test
    @DisplayName("Gauge metrics should be registered in meter registry")
    void gaugeMetrics_registered() {
        assertNotNull(meterRegistry.find("rag.requests.total").counter());
        assertNotNull(meterRegistry.find("rag.requests.success").counter());
        assertNotNull(meterRegistry.find("rag.requests.failed").counter());
        assertNotNull(meterRegistry.find("rag.retrieval.results.total").gauge());
        assertNotNull(meterRegistry.find("rag.llm.tokens.total").gauge());
    }

    @Test
    @DisplayName("Timer should be registered with correct name")
    void timerRegistered() {
        assertNotNull(meterRegistry.find("rag.response.time").timer());
    }
}
