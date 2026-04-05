package com.springairag.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelMetricsService 单元测试
 */
@DisplayName("ModelMetricsService Tests")
class ModelMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private ModelMetricsService modelMetricsService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        modelMetricsService = new ModelMetricsService(meterRegistry);
    }

    @Test
    @DisplayName("Initial call count should be zero")
    void getCallCount_initiallyZero() {
        assertEquals(0, modelMetricsService.getCallCount("deepseek"));
    }

    @Test
    @DisplayName("Initial error count should be zero")
    void getErrorCount_initiallyZero() {
        assertEquals(0, modelMetricsService.getErrorCount("deepseek"));
    }

    @Test
    @DisplayName("Initial error rate should be zero")
    void getErrorRate_initiallyZero() {
        assertEquals(0.0, modelMetricsService.getErrorRate("deepseek"));
    }

    @Test
    @DisplayName("recordSuccess should increment call counter")
    void recordSuccess_incrementsCallCount() {
        modelMetricsService.recordSuccess("deepseek", 150);

        assertEquals(1, modelMetricsService.getCallCount("deepseek"));
        assertEquals(0, modelMetricsService.getErrorCount("deepseek"));
    }

    @Test
    @DisplayName("recordSuccess should not affect error rate")
    void recordSuccess_doesNotAffectErrorRate() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("deepseek", 200);

        assertEquals(0.0, modelMetricsService.getErrorRate("deepseek"));
    }

    @Test
    @DisplayName("recordError should increment both call and error counters")
    void recordError_incrementsBoth() {
        modelMetricsService.recordError("anthropic", 300);

        assertEquals(1, modelMetricsService.getCallCount("anthropic"));
        assertEquals(1, modelMetricsService.getErrorCount("anthropic"));
    }

    @Test
    @DisplayName("recordError should result in 100% error rate for single call")
    void recordError_singleCall_100PercentErrorRate() {
        modelMetricsService.recordError("anthropic", 100);

        assertEquals(1.0, modelMetricsService.getErrorRate("anthropic"));
    }

    @Test
    @DisplayName("Multiple calls should track correctly")
    void mixedCalls_tracksCorrectly() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("deepseek", 200);
        modelMetricsService.recordError("deepseek", 300);
        modelMetricsService.recordSuccess("deepseek", 50);

        // 3 successes, 1 error out of 4 total
        assertEquals(4, modelMetricsService.getCallCount("deepseek"));
        assertEquals(1, modelMetricsService.getErrorCount("deepseek"));
        assertEquals(0.25, modelMetricsService.getErrorRate("deepseek"), 0.001);
    }

    @Test
    @DisplayName("Different providers should have independent metrics")
    void differentProviders_independentMetrics() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("anthropic", 200);
        modelMetricsService.recordError("anthropic", 300);

        assertEquals(1, modelMetricsService.getCallCount("deepseek"));
        assertEquals(0, modelMetricsService.getErrorCount("deepseek"));

        assertEquals(2, modelMetricsService.getCallCount("anthropic"));
        assertEquals(1, modelMetricsService.getErrorCount("anthropic"));
    }

    @Test
    @DisplayName("Timer should be registered per provider")
    void timerRegisteredPerProvider() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("anthropic", 200);

        assertNotNull(meterRegistry.find("rag.model.latency").timer());
    }

    @Test
    @DisplayName("Counters should be tagged with provider name")
    void countersTaggedWithProvider() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("minimax", 200);

        // Both should be registered under the same meter name with different tags
        assertEquals(1, meterRegistry.find("rag.model.calls.total").tag("provider", "deepseek").counter().count());
        assertEquals(1, meterRegistry.find("rag.model.calls.total").tag("provider", "minimax").counter().count());
    }

    @Test
    @DisplayName("Error rate for non-existent provider should be zero")
    void errorRate_nonExistent_returnsZero() {
        assertEquals(0.0, modelMetricsService.getErrorRate("nonexistent"));
    }
}
