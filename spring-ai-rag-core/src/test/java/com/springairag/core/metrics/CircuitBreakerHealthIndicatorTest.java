package com.springairag.core.metrics;

import com.springairag.core.config.RagChatService;
import com.springairag.core.config.RagCircuitBreakerProperties;
import com.springairag.core.resilience.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CircuitBreakerHealthIndicator 单元测试
 */
class CircuitBreakerHealthIndicatorTest {

    @Test
    void health_whenCircuitBreakerNull_returnsUnknown() {
        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(null);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("enabled"));
        assertEquals("NOT_CONFIGURED", health.getDetails().get("state"));
    }

    @Test
    void health_whenCircuitClosed_returnsUp() {
        RagCircuitBreakerProperties config = new RagCircuitBreakerProperties();
        config.setEnabled(true);
        config.setFailureRateThreshold(50);
        config.setMinimumNumberOfCalls(10);
        config.setWaitDurationInOpenStateSeconds(30);
        config.setSlidingWindowSize(20);

        LlmCircuitBreaker circuitBreaker = new LlmCircuitBreaker(config);
        // 模拟一些成功调用
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(circuitBreaker);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("CLOSED", health.getDetails().get("state"));
        assertEquals(2, health.getDetails().get("successes"));
        assertEquals(0, health.getDetails().get("failures"));
    }

    @Test
    void health_whenCircuitOpen_returnsDown() {
        RagCircuitBreakerProperties config = new RagCircuitBreakerProperties();
        config.setEnabled(true);
        config.setFailureRateThreshold(50);
        config.setMinimumNumberOfCalls(10);
        config.setWaitDurationInOpenStateSeconds(30);
        config.setSlidingWindowSize(20);

        LlmCircuitBreaker circuitBreaker = new LlmCircuitBreaker(config);
        // 模拟触发熔断：连续失败直到失败率达到阈值
        for (int i = 0; i < 15; i++) {
            circuitBreaker.recordFailure();
        }

        // 验证熔断器已打开
        assertEquals(LlmCircuitBreaker.State.OPEN, circuitBreaker.getState());

        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(circuitBreaker);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("OPEN", health.getDetails().get("state"));
        assertTrue((Integer) health.getDetails().get("failures") > 0);
    }

    @Test
    void health_whenCircuitHalfOpen_returnsUnknown() {
        RagCircuitBreakerProperties config = new RagCircuitBreakerProperties();
        config.setEnabled(true);
        config.setFailureRateThreshold(50);
        config.setMinimumNumberOfCalls(5);
        config.setWaitDurationInOpenStateSeconds(1);  // 1秒后进入 HALF_OPEN
        config.setSlidingWindowSize(10);

        LlmCircuitBreaker circuitBreaker = new LlmCircuitBreaker(config);
        // 触发熔断
        for (int i = 0; i < 6; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(LlmCircuitBreaker.State.OPEN, circuitBreaker.getState());

        // 等待冷却时间后尝试重置
        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}
        circuitBreaker.allowCall();  // 触发 OPEN -> HALF_OPEN

        assertEquals(LlmCircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(circuitBreaker);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        // HALF_OPEN 返回 UNKNOWN（探测中）
        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("HALF_OPEN", health.getDetails().get("state"));
    }

    @Test
    void health_includesFailureRateAndThreshold() {
        RagCircuitBreakerProperties config = new RagCircuitBreakerProperties();
        config.setEnabled(true);
        config.setFailureRateThreshold(50);
        config.setMinimumNumberOfCalls(10);
        config.setWaitDurationInOpenStateSeconds(30);
        config.setSlidingWindowSize(20);

        LlmCircuitBreaker circuitBreaker = new LlmCircuitBreaker(config);
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(circuitBreaker);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("50.0%", health.getDetails().get("failureRate"));
        assertEquals("50%", health.getDetails().get("failureRateThreshold"));
    }

    @Test
    void health_includesLastFailureAgeMs_whenOpen() {
        RagCircuitBreakerProperties config = new RagCircuitBreakerProperties();
        config.setEnabled(true);
        config.setFailureRateThreshold(50);
        config.setMinimumNumberOfCalls(5);
        config.setWaitDurationInOpenStateSeconds(60);
        config.setSlidingWindowSize(10);

        LlmCircuitBreaker circuitBreaker = new LlmCircuitBreaker(config);
        circuitBreaker.recordFailure();

        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(circuitBreaker);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        // CLOSED 状态 lastFailureAgeMs 应该是 0
        assertEquals(0L, health.getDetails().get("lastFailureAgeMs"));
    }
}
