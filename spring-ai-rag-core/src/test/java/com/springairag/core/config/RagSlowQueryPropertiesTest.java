package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagSlowQueryProperties.
 */
class RagSlowQueryPropertiesTest {

    @Test
    void defaults_enabledIsTrue() {
        RagSlowQueryProperties props = new RagSlowQueryProperties();
        assertTrue(props.isEnabled());
    }

    @Test
    void defaults_thresholdMsIs1000() {
        RagSlowQueryProperties props = new RagSlowQueryProperties();
        assertEquals(1000, props.getThresholdMs());
    }

    @Test
    void defaults_logEnabledIsTrue() {
        RagSlowQueryProperties props = new RagSlowQueryProperties();
        assertTrue(props.isLogEnabled());
    }

    @Test
    void defaults_maxRetainedIs100() {
        RagSlowQueryProperties props = new RagSlowQueryProperties();
        assertEquals(100, props.getMaxRetained());
    }

    @Test
    void setters_updateValues() {
        RagSlowQueryProperties props = new RagSlowQueryProperties();
        props.setEnabled(false);
        props.setThresholdMs(500);
        props.setLogEnabled(false);
        props.setMaxRetained(50);

        assertFalse(props.isEnabled());
        assertEquals(500, props.getThresholdMs());
        assertFalse(props.isLogEnabled());
        assertEquals(50, props.getMaxRetained());
    }
}
