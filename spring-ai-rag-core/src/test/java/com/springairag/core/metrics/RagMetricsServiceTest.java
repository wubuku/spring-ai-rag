package com.springairag.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagMetricsService 单元测试
 */
class RagMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private RagMetricsService metricsService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricsService = new RagMetricsService(meterRegistry);
    }

    @Test
    @DisplayName("记录成功请求后计数器递增")
    void recordSuccess_incrementsCounters() {
        metricsService.recordSuccess(100, 5);

        assertEquals(1, metricsService.getTotalRequests());
        assertEquals(1, metricsService.getSuccessfulRequests());
        assertEquals(0, metricsService.getFailedRequests());
        assertEquals(100.0, metricsService.getSuccessRate());
    }

    @Test
    @DisplayName("记录失败请求后计数器递增")
    void recordFailure_incrementsCounters() {
        metricsService.recordFailure(200);

        assertEquals(1, metricsService.getTotalRequests());
        assertEquals(0, metricsService.getSuccessfulRequests());
        assertEquals(1, metricsService.getFailedRequests());
        assertEquals(0.0, metricsService.getSuccessRate());
    }

    @Test
    @DisplayName("多次请求成功率计算正确")
    void multipleRequests_successRateCalculated() {
        metricsService.recordSuccess(100, 3);
        metricsService.recordSuccess(150, 5);
        metricsService.recordFailure(200);

        assertEquals(3, metricsService.getTotalRequests());
        assertEquals(2, metricsService.getSuccessfulRequests());
        assertEquals(1, metricsService.getFailedRequests());

        double expectedRate = 2.0 / 3.0 * 100;
        assertEquals(expectedRate, metricsService.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("记录 LLM token 不影响请求计数")
    void recordLlmTokens_doesNotAffectRequestCount() {
        metricsService.recordLlmTokens(1000);
        metricsService.recordLlmTokens(500);

        assertEquals(0, metricsService.getTotalRequests());
    }

    @Test
    @DisplayName("空服务成功率默认 100%")
    void emptyService_successRateIs100() {
        assertEquals(100.0, metricsService.getSuccessRate());
    }

    @Test
    @DisplayName("Micrometer 计时器记录了响应时间")
    void responseTimer_recordsDuration() {
        metricsService.recordSuccess(50, 1);
        metricsService.recordSuccess(150, 2);
        metricsService.recordSuccess(300, 1);

        // 验证 Micrometer Timer 已记录
        assertEquals(3, meterRegistry.get("rag.response.time").timer().count());
        assertTrue(meterRegistry.get("rag.response.time").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 500);
    }

    @Test
    @DisplayName("Micrometer 计数器与内部计数一致")
    void micrometerCounters_matchInternal() {
        metricsService.recordSuccess(100, 1);
        metricsService.recordSuccess(200, 2);
        metricsService.recordFailure(300);

        assertEquals(3, meterRegistry.get("rag.requests.total").counter().count(), 0.01);
        assertEquals(2, meterRegistry.get("rag.requests.success").counter().count(), 0.01);
        assertEquals(1, meterRegistry.get("rag.requests.failed").counter().count(), 0.01);
    }
}
