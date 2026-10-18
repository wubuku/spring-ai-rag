package com.springairag.core.resilience;

import com.springairag.core.config.RagCircuitBreakerProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitIgnoredTest {
    @Test
    void ignoredBusinessFailureReleasesHalfOpenProbe() {
        var properties = new RagCircuitBreakerProperties();
        properties.setMinimumNumberOfCalls(1);
        properties.setFailureRateThreshold(1);
        properties.setWaitDurationInOpenStateSeconds(0);
        var breaker = new LlmCircuitBreaker(properties);
        breaker.recordFailure();
        assertTrue(breaker.allowCall());
        assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());
        breaker.recordIgnored();
        assertTrue(breaker.allowCall());
        assertFalse(breaker.allowCall());
        breaker.recordSuccess();
        assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
    }
}
