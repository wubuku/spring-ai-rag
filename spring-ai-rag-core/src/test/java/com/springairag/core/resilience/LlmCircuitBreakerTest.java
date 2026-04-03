package com.springairag.core.resilience;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 熔断器完整测试
 */
class LlmCircuitBreakerTest {

    private LlmCircuitBreaker breaker;

    private static RagProperties.CircuitBreaker buildConfig(
            int failureRate, int minCalls, int waitSeconds, int windowSize) {
        RagProperties.CircuitBreaker config = new RagProperties.CircuitBreaker();
        config.setFailureRateThreshold(failureRate);
        config.setMinimumNumberOfCalls(minCalls);
        config.setWaitDurationInOpenStateSeconds(waitSeconds);
        config.setSlidingWindowSize(windowSize);
        return config;
    }

    @BeforeEach
    void setUp() {
        breaker = new LlmCircuitBreaker(buildConfig(50, 10, 1, 20));
    }

    // ==================== CLOSED state ====================

    @Nested
    @DisplayName("CLOSED 状态")
    class ClosedState {

        @Test
        @DisplayName("初始允许调用")
        void allowCallInitially() {
            assertTrue(breaker.allowCall());
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("未达到最小调用数时不会熔断")
        void noTripBelowMinimumCalls() {
            for (int i = 0; i < 9; i++) {
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.allowCall());
        }

        @Test
        @DisplayName("失败率 50% 时熔断（>= 阈值触发）")
        void tripAtExactThreshold() {
            // 10 successes + 10 failures = 50% failure rate = threshold, so trips (>=)
            for (int i = 0; i < 10; i++) {
                breaker.recordSuccess();
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("失败率刚好低于阈值时不会熔断")
        void noTripBelowExactThreshold() {
            // Use a fresh breaker with 51% threshold so 50% is below it
            RagProperties.CircuitBreaker config = buildConfig(51, 10, 1, 20);
            LlmCircuitBreaker freshBreaker = new LlmCircuitBreaker(config);
            // 10S + 10F = 50% < 51% threshold, should stay CLOSED
            for (int i = 0; i < 10; i++) {
                freshBreaker.recordSuccess();
                freshBreaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.CLOSED, freshBreaker.getState());
        }

        @Test
        @DisplayName("失败率超过 50% 时熔断")
        void tripAboveThreshold() {
            // 10 failures before 10 successes → 100% failure rate
            for (int i = 0; i < 10; i++) {
                breaker.recordFailure();
            }
            // After 10 failures (100% > 50%), should transition to OPEN
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("成功后失败计数重置")
        void successesResetAfterFailure() {
            breaker.recordFailure();
            breaker.recordFailure();
            breaker.recordFailure();
            breaker.recordSuccess();
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
            assertEquals(1, breaker.getSuccesses());
        }
    }

    // ==================== OPEN state ====================

    @Nested
    @DisplayName("OPEN 状态")
    class OpenState {

        @BeforeEach
        void tripCircuit() {
            // Trip: 10 failures → 100% failure rate
            for (int i = 0; i < 10; i++) {
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("OPEN 状态拒绝调用")
        void rejectCallInOpenState() {
            assertFalse(breaker.allowCall());
        }

        @Test
        @DisplayName("OPEN 状态经过等待时间后进入 HALF_OPEN")
        void transitionToHalfOpenAfterWait() throws InterruptedException {
            // Wait for the 1 second wait duration
            Thread.sleep(1100);
            assertTrue(breaker.allowCall());
            assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());
        }
    }

    // ==================== HALF_OPEN state ====================

    @Nested
    @DisplayName("HALF_OPEN 状态")
    class HalfOpenState {

        @BeforeEach
        void enterHalfOpen() throws InterruptedException {
            // Trip the circuit
            for (int i = 0; i < 10; i++) {
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
            // Wait and trigger transition
            Thread.sleep(1100);
            assertTrue(breaker.allowCall());
            assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());
        }

        @Test
        @DisplayName("HALF_OPEN 成功调用后回到 CLOSED")
        void closeAfterSuccess() {
            breaker.recordSuccess();
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("HALF_OPEN 失败调用后回到 OPEN")
        void reOpenAfterFailure() {
            breaker.recordFailure();
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("HALF_OPEN 再次调用被拒绝（同时只允许一个测试调用）")
        void secondCallRejectedInHalfOpen() {
            assertTrue(breaker.allowCall()); // First call allowed
            assertFalse(breaker.allowCall()); // Second rejected
        }
    }

    // ==================== getStats ====================

    @Test
    @DisplayName("getStats 返回正确的统计信息")
    void getStatsFormat() {
        String stats = breaker.getStats();
            assertTrue(stats.contains("state=CLOSED"));
            assertTrue(stats.contains("successes="));
            assertTrue(stats.contains("failures="));
    }

    @Test
    @DisplayName("OPEN 状态下 getStats 包含 lastFailureAgeMs")
    void openStateStatsContainLastFailureAge() {
        for (int i = 0; i < 10; i++) {
            breaker.recordFailure();
        }
        String stats = breaker.getStats();
        assertTrue(stats.contains("state=OPEN"));
        assertTrue(stats.contains("lastFailureAgeMs="));
    }

    // ==================== Sliding window ====================

    @Nested
    @DisplayName("滑动窗口边界")
    class SlidingWindow {

        @Test
        @DisplayName("大窗口内重置计数防止溢出")
        void largeWindowResetPreventsOverflow() {
            RagProperties.CircuitBreaker config = buildConfig(50, 100, 1, 20);
            LlmCircuitBreaker smallWindow = new LlmCircuitBreaker(config);
            // Add many more failures than window size
            for (int i = 0; i < 30; i++) {
                smallWindow.recordFailure();
            }
            // Should not overflow (internal count capped)
            assertTrue(smallWindow.getFailures() <= 20);
        }
    }
}
