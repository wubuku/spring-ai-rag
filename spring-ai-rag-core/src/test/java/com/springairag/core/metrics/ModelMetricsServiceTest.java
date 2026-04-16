package com.springairag.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

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

    @Test
    @DisplayName("Timer should accumulate count across multiple recordings")
    void timer_accumulatesCountAcrossMultipleRecordings() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("deepseek", 200);
        modelMetricsService.recordSuccess("deepseek", 300);

        Timer timer = meterRegistry.find("rag.model.latency").tag("provider", "deepseek").timer();
        assertNotNull(timer);
        assertEquals(3, timer.count()); // 3 recordings
    }

    @Test
    @DisplayName("Timer should accumulate total time correctly")
    void timer_accumulatesTotalTime() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("deepseek", 200);
        modelMetricsService.recordSuccess("deepseek", 300);

        Timer timer = meterRegistry.find("rag.model.latency").tag("provider", "deepseek").timer();
        assertNotNull(timer);
        // Total time = 100 + 200 + 300 = 600ms
        assertEquals(600, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
    }

    @Test
    @DisplayName("Zero-latency call should be recorded correctly")
    void zeroLatency_call_recorded() {
        modelMetricsService.recordSuccess("deepseek", 0);

        assertEquals(1, modelMetricsService.getCallCount("deepseek"));
        assertEquals(0.0, modelMetricsService.getErrorRate("deepseek"));
    }

    @Test
    @DisplayName("Error call should also record latency")
    void error_call_recordsLatency() {
        modelMetricsService.recordError("anthropic", 500);

        Timer timer = meterRegistry.find("rag.model.latency").tag("provider", "anthropic").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(500, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
    }

    @Test
    @DisplayName("Multiple providers should each have independent timers")
    void multipleProviders_independentTimers() {
        modelMetricsService.recordSuccess("deepseek", 100);
        modelMetricsService.recordSuccess("anthropic", 200);
        modelMetricsService.recordSuccess("deepseek", 150);
        modelMetricsService.recordError("anthropic", 300);

        Timer deepseekTimer = meterRegistry.find("rag.model.latency").tag("provider", "deepseek").timer();
        Timer anthropicTimer = meterRegistry.find("rag.model.latency").tag("provider", "anthropic").timer();

        assertNotNull(deepseekTimer);
        assertNotNull(anthropicTimer);
        assertEquals(2, deepseekTimer.count()); // 2 deepseek calls
        assertEquals(2, anthropicTimer.count()); // 2 anthropic calls (1 success + 1 error)
        assertEquals(250, deepseekTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
        assertEquals(500, anthropicTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
    }
}
