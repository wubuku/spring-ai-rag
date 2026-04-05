package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RagRetryProperties}.
 */
class RagRetryPropertiesTest {

    @Test
    void defaultValues() {
        RagRetryProperties props = new RagRetryProperties();

        assertTrue(props.isEnabled());
        assertEquals(3, props.getMaxAttempts());
        assertEquals(1_000, props.getInitialBackoffMs());
        assertEquals(10_000, props.getMaxBackoffMs());
        assertEquals(2.0, props.getBackoffMultiplier());
        assertTrue(props.isRetryOnRateLimit());
        assertTrue(props.isRetryOnServiceUnavailable());
        assertTrue(props.isRetryOnConnectTimeout());
        assertTrue(props.isRetryOnReadTimeout());
        assertEquals(0.2, props.getJitterFactor());
    }

    @Test
    void settersAndGetters() {
        RagRetryProperties props = new RagRetryProperties();

        props.setEnabled(false);
        assertFalse(props.isEnabled());

        props.setMaxAttempts(5);
        assertEquals(5, props.getMaxAttempts());

        props.setInitialBackoffMs(500);
        assertEquals(500, props.getInitialBackoffMs());

        props.setMaxBackoffMs(30_000);
        assertEquals(30_000, props.getMaxBackoffMs());

        props.setBackoffMultiplier(3.0);
        assertEquals(3.0, props.getBackoffMultiplier());

        props.setRetryOnRateLimit(false);
        assertFalse(props.isRetryOnRateLimit());

        props.setRetryOnServiceUnavailable(false);
        assertFalse(props.isRetryOnServiceUnavailable());

        props.setRetryOnConnectTimeout(false);
        assertFalse(props.isRetryOnConnectTimeout());

        props.setRetryOnReadTimeout(false);
        assertFalse(props.isRetryOnReadTimeout());

        props.setJitterFactor(0.5);
        assertEquals(0.5, props.getJitterFactor());
    }

    @Test
    void disabledRetryHasMaxAttemptsOf1() {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(false);
        props.setMaxAttempts(1); // effectively disables retry

        assertFalse(props.isEnabled());
        assertEquals(1, props.getMaxAttempts());
    }
}
