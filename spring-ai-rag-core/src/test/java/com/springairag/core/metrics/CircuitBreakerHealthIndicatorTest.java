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
 * CircuitBreakerHealthIndicator Unit Tests
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
        // Simulate some successful calls
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
        // Simulate circuit breaker opening: consecutive failures until failure rate threshold
        for (int i = 0; i < 15; i++) {
            circuitBreaker.recordFailure();
        }

        // Verify the circuit breaker is open
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
        config.setWaitDurationInOpenStateSeconds(1);  // Wait 1 second to enter HALF_OPEN
        config.setSlidingWindowSize(10);

        LlmCircuitBreaker circuitBreaker = new LlmCircuitBreaker(config);
        // Trigger circuit breaker opening
        for (int i = 0; i < 6; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(LlmCircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for cooldown then attempt reset
        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}
        circuitBreaker.allowCall();  // Trigger OPEN -> HALF_OPEN

        assertEquals(LlmCircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        RagChatService ragChatService = mock(RagChatService.class);
        when(ragChatService.getCircuitBreaker()).thenReturn(circuitBreaker);

        CircuitBreakerHealthIndicator indicator = new CircuitBreakerHealthIndicator(ragChatService);
        Health health = indicator.health();

        // HALF_OPEN returns UNKNOWN (probing)
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

        // In CLOSED state, lastFailureAgeMs should be 0
        assertEquals(0L, health.getDetails().get("lastFailureAgeMs"));
    }
}
