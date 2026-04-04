package com.springairag.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelMetricsService 单元测试
 */
class ModelMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private ModelMetricsService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ModelMetricsService(meterRegistry);
    }

    @Test
    @DisplayName("recordSuccess 增加调用计数和延迟")
    void testRecordSuccess() {
        service.recordSuccess("openai", 100);
        service.recordSuccess("openai", 200);

        assertEquals(2, service.getCallCount("openai"));
        assertEquals(0, service.getErrorCount("openai"));
        assertEquals(0.0, service.getErrorRate("openai"));
    }

    @Test
    @DisplayName("recordError 增加调用计数和错误计数")
    void testRecordError() {
        service.recordError("minimax", 50);
        service.recordError("minimax", 50);

        assertEquals(2, service.getCallCount("minimax"));
        assertEquals(2, service.getErrorCount("minimax"));
        assertEquals(1.0, service.getErrorRate("minimax")); // 2/2 = 100%
    }

    @Test
    @DisplayName("混合 recordSuccess 和 recordError")
    void testMixed() {
        service.recordSuccess("openai", 100);
        service.recordError("openai", 50);
        service.recordSuccess("openai", 150);

        assertEquals(3, service.getCallCount("openai"));
        assertEquals(1, service.getErrorCount("openai"));
        assertEquals(1.0 / 3.0, service.getErrorRate("openai"), 0.001);
    }

    @Test
    @DisplayName("未记录的 provider 返回 0")
    void testUnknownProvider() {
        assertEquals(0, service.getCallCount("unknown"));
        assertEquals(0, service.getErrorCount("unknown"));
        assertEquals(0.0, service.getErrorRate("unknown"));
    }

    @Test
    @DisplayName("错误率为 0 当无调用")
    void testErrorRateWithNoCalls() {
        assertEquals(0.0, service.getErrorRate("openai"));
    }

    @Test
    @DisplayName("Micrometer Counter 注册成功")
    void testMicrometerCounterRegistered() {
        service.recordSuccess("openai", 100);

        Counter counter = meterRegistry.find("rag.model.calls.total").tag("provider", "openai").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }
}
