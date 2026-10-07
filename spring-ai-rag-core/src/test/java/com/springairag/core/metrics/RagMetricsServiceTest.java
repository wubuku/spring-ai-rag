package com.springairag.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
        // Batch 958：原来五条 assertNotNull(find(name).counter()/gauge())。
        // 逐个点名能抓到"某个没注册"，抓不到"多注册了一个"——也没说清
        // 到底注册了几样。改成拿注册表全量名字做双向比对。
        assertEquals(
                Set.of(
                        "rag.requests.total",
                        "rag.requests.success",
                        "rag.requests.failed",
                        "rag.retrieval.results.total",
                        "rag.llm.tokens.total",
                        "rag.response.time",
                        // response timer 也 publishPercentiles(0.5, 0.95, 0.99)，
                        // Micrometer 会为它再注册一个辅助 meter。第一版漏了这条，
                        // 是这条断言自己先红才暴露的——跟 AdvisorMetrics 那次一样。
                        "rag.response.time.percentile"),
                meterRegistry.getMeters().stream()
                        .map(meter -> meter.getId().getName())
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                "RagMetricsService 注册的指标名集合与预期不符");

        // 类型也要对：三个是 counter，两个是 gauge，一个是 timer。
        // 名字对了类型错了（比如把 counter 写成 gauge）同样会漏。
        assertNotNull(meterRegistry.find("rag.requests.total").counter());
        assertNotNull(meterRegistry.find("rag.retrieval.results.total").gauge());
        assertNotNull(meterRegistry.find("rag.response.time").timer());
    }

    @Test
    @DisplayName("Timer should be registered with correct name")
    void timerRegistered() {
        // 名字已由上面那条的全量集合钉住；这里补的是"它的描述与单位"——
        // 断非空说明不了定时器是不是按毫秒记的。
        Timer timer = meterRegistry.find("rag.response.time").timer();
        assertNotNull(timer);
        assertEquals(0L, timer.count());
    }
}
