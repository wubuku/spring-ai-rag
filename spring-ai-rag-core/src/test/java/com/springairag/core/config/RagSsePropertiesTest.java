package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagSseProperties.
 */
class RagSsePropertiesTest {

    @Test
    void defaults_heartbeatIntervalIs30() {
        RagSseProperties props = new RagSseProperties();
        assertEquals(30, props.getHeartbeatIntervalSeconds());
    }

    @Test
    void defaults_heartbeatEnabledIsTrue() {
        RagSseProperties props = new RagSseProperties();
        assertTrue(props.isHeartbeatEnabled());
    }

    @Test
    void heartbeatIntervalZero_disablesHeartbeat() {
        RagSseProperties props = new RagSseProperties();
        props.setHeartbeatIntervalSeconds(0);
        assertFalse(props.isHeartbeatEnabled());
    }

    @Test
    void heartbeatIntervalNegative_disablesHeartbeat() {
        RagSseProperties props = new RagSseProperties();
        props.setHeartbeatIntervalSeconds(-5);
        assertFalse(props.isHeartbeatEnabled());
    }

    @Test
    void setters_updateValues() {
        RagSseProperties props = new RagSseProperties();
        props.setHeartbeatIntervalSeconds(60);
        assertEquals(60, props.getHeartbeatIntervalSeconds());
        assertTrue(props.isHeartbeatEnabled());
    }
}
