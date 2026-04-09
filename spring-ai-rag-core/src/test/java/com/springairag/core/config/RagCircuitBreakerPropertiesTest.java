package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagCircuitBreakerProperties.
 */
class RagCircuitBreakerPropertiesTest {

    @Test
    void defaults_enabledIsFalse() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        assertFalse(props.isEnabled());
    }

    @Test
    void defaults_failureRateThresholdIs50() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        assertEquals(50, props.getFailureRateThreshold());
    }

    @Test
    void defaults_minimumNumberOfCallsIs10() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        assertEquals(10, props.getMinimumNumberOfCalls());
    }

    @Test
    void defaults_waitDurationInOpenStateSecondsIs30() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        assertEquals(30, props.getWaitDurationInOpenStateSeconds());
    }

    @Test
    void defaults_slidingWindowSizeIs20() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        assertEquals(20, props.getSlidingWindowSize());
    }

    @Test
    void setters_updateValues() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        props.setEnabled(true);
        props.setFailureRateThreshold(75);
        props.setMinimumNumberOfCalls(20);
        props.setWaitDurationInOpenStateSeconds(60);
        props.setSlidingWindowSize(50);

        assertTrue(props.isEnabled());
        assertEquals(75, props.getFailureRateThreshold());
        assertEquals(20, props.getMinimumNumberOfCalls());
        assertEquals(60, props.getWaitDurationInOpenStateSeconds());
        assertEquals(50, props.getSlidingWindowSize());
    }

    @Test
    void allDefaultsFormValidConfiguration() {
        RagCircuitBreakerProperties props = new RagCircuitBreakerProperties();
        // All values should be positive and within reasonable ranges
        assertTrue(props.getFailureRateThreshold() > 0 && props.getFailureRateThreshold() <= 100);
        assertTrue(props.getMinimumNumberOfCalls() > 0);
        assertTrue(props.getWaitDurationInOpenStateSeconds() > 0);
        assertTrue(props.getSlidingWindowSize() > 0);
    }
}
