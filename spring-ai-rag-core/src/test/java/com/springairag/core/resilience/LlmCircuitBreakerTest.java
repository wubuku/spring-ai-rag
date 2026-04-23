package com.springairag.core.resilience;

import com.springairag.core.config.RagCircuitBreakerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for LLM Circuit Breaker
 */
class LlmCircuitBreakerTest {

    private LlmCircuitBreaker breaker;

    private static RagCircuitBreakerProperties buildConfig(
            int failureRate, int minCalls, int waitSeconds, int windowSize) {
        RagCircuitBreakerProperties config = new RagCircuitBreakerProperties();
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
    @DisplayName("CLOSED State")
    class ClosedState {

        @Test
        @DisplayName("Allows calls initially")
        void allowCallInitially() {
            assertTrue(breaker.allowCall());
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("Does not trip below minimum call count")
        void noTripBelowMinimumCalls() {
            for (int i = 0; i < 9; i++) {
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.allowCall());
        }

        @Test
        @DisplayName("Trips at 50% failure rate (>= threshold triggers)")
        void tripAtExactThreshold() {
            // 10 successes + 10 failures = 50% failure rate = threshold, so trips (>=)
            for (int i = 0; i < 10; i++) {
                breaker.recordSuccess();
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("Does not trip when failure rate is just below threshold")
        void noTripBelowExactThreshold() {
            // Use a fresh breaker with 51% threshold so 50% is below it
            RagCircuitBreakerProperties config = buildConfig(51, 10, 1, 20);
            LlmCircuitBreaker freshBreaker = new LlmCircuitBreaker(config);
            // 10S + 10F = 50% < 51% threshold, should stay CLOSED
            for (int i = 0; i < 10; i++) {
                freshBreaker.recordSuccess();
                freshBreaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.CLOSED, freshBreaker.getState());
        }

        @Test
        @DisplayName("Trips when failure rate exceeds 50%")
        void tripAboveThreshold() {
            // 10 failures before 10 successes → 100% failure rate
            for (int i = 0; i < 10; i++) {
                breaker.recordFailure();
            }
            // After 10 failures (100% > 50%), should transition to OPEN
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("Success resets failure count")
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
    @DisplayName("OPEN State")
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
        @DisplayName("Rejects calls in OPEN state")
        void rejectCallInOpenState() {
            assertFalse(breaker.allowCall());
        }

        @Test
        @DisplayName("Transitions to HALF_OPEN after wait duration")
        void transitionToHalfOpenAfterWait() throws InterruptedException {
            // Wait for the 1 second wait duration
            Thread.sleep(1100);
            assertTrue(breaker.allowCall());
            assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());
        }
    }

    // ==================== HALF_OPEN state ====================

    @Nested
    @DisplayName("HALF_OPEN State")
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
        @DisplayName("Returns to CLOSED after success in HALF_OPEN")
        void closeAfterSuccess() {
            breaker.recordSuccess();
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("Returns to OPEN after failure in HALF_OPEN")
        void reOpenAfterFailure() {
            breaker.recordFailure();
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("Rejects second call in HALF_OPEN (single probing call only)")
        void secondCallRejectedInHalfOpen() {
            assertTrue(breaker.allowCall()); // First call allowed
            assertFalse(breaker.allowCall()); // Second rejected
        }
    }

    // ==================== getStats ====================

    @Test
    @DisplayName("getStats returns correct statistics")
    void getStatsFormat() {
        String stats = breaker.getStats();
            assertTrue(stats.contains("state=CLOSED"));
            assertTrue(stats.contains("successes="));
            assertTrue(stats.contains("failures="));
    }

    @Test
    @DisplayName("getStats includes lastFailureAgeMs in OPEN state")
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
    @DisplayName("Sliding Window Boundaries")
    class SlidingWindow {

        @Test
        @DisplayName("Large window reset prevents overflow")
        void largeWindowResetPreventsOverflow() {
            RagCircuitBreakerProperties config = buildConfig(50, 100, 1, 20);
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
