package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmbeddingCircuitBreakerProperties.
 */
class EmbeddingCircuitBreakerPropertiesTest {

    @Test
    void defaults_areCorrect() {
        EmbeddingCircuitBreakerProperties props = new EmbeddingCircuitBreakerProperties();
        assertFalse(props.isEnabled());
        assertEquals(50, props.getFailureRateThreshold());
        assertEquals(10, props.getMinimumNumberOfCalls());
        assertEquals(30, props.getWaitDurationInOpenStateSeconds());
        assertEquals(20, props.getSlidingWindowSize());
    }

    @Test
    void setters_updateAllValues() {
        EmbeddingCircuitBreakerProperties props = new EmbeddingCircuitBreakerProperties();

        props.setEnabled(true);
        props.setFailureRateThreshold(60);
        props.setMinimumNumberOfCalls(20);
        props.setWaitDurationInOpenStateSeconds(60);
        props.setSlidingWindowSize(100);

        assertTrue(props.isEnabled());
        assertEquals(60, props.getFailureRateThreshold());
        assertEquals(20, props.getMinimumNumberOfCalls());
        assertEquals(60, props.getWaitDurationInOpenStateSeconds());
        assertEquals(100, props.getSlidingWindowSize());
    }

    @Test
    void setters_acceptBoundaryValues() {
        EmbeddingCircuitBreakerProperties props = new EmbeddingCircuitBreakerProperties();

        props.setEnabled(false);
        props.setFailureRateThreshold(0);
        props.setMinimumNumberOfCalls(0);
        props.setWaitDurationInOpenStateSeconds(0);
        props.setSlidingWindowSize(0);

        assertFalse(props.isEnabled());
        assertEquals(0, props.getFailureRateThreshold());
        assertEquals(0, props.getMinimumNumberOfCalls());
        assertEquals(0, props.getWaitDurationInOpenStateSeconds());
        assertEquals(0, props.getSlidingWindowSize());
    }
}
