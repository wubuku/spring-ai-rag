package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagRateLimitProperties.
 */
class RagRateLimitPropertiesTest {

    @Test
    void defaults_enabledIsFalse() {
        RagRateLimitProperties props = new RagRateLimitProperties();
        assertFalse(props.isEnabled());
    }

    @Test
    void defaults_requestsPerMinuteIs60() {
        RagRateLimitProperties props = new RagRateLimitProperties();
        assertEquals(60, props.getRequestsPerMinute());
    }

    @Test
    void defaults_strategyIsIp() {
        RagRateLimitProperties props = new RagRateLimitProperties();
        assertEquals("ip", props.getStrategy());
    }

    @Test
    void defaults_keyLimitsIsEmptyMap() {
        RagRateLimitProperties props = new RagRateLimitProperties();
        assertNotNull(props.getKeyLimits());
        assertTrue(props.getKeyLimits().isEmpty());
    }

    @Test
    void setters_updateAllValues() {
        RagRateLimitProperties props = new RagRateLimitProperties();

        props.setEnabled(true);
        props.setRequestsPerMinute(120);
        props.setStrategy("user");

        Map<String, Integer> keyLimits = new HashMap<>();
        keyLimits.put("sk-premium", 300);
        keyLimits.put("sk-basic", 100);
        props.setKeyLimits(keyLimits);

        assertTrue(props.isEnabled());
        assertEquals(120, props.getRequestsPerMinute());
        assertEquals("user", props.getStrategy());
        assertEquals(2, props.getKeyLimits().size());
        assertEquals(300, props.getKeyLimits().get("sk-premium"));
        assertEquals(100, props.getKeyLimits().get("sk-basic"));
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagRateLimitProperties props = new RagRateLimitProperties();

        props.setEnabled(false);
        props.setRequestsPerMinute(0);
        props.setStrategy("ip");
        props.setKeyLimits(new HashMap<>());

        assertFalse(props.isEnabled());
        assertEquals(0, props.getRequestsPerMinute());
        assertEquals("ip", props.getStrategy());
        assertTrue(props.getKeyLimits().isEmpty());
    }

    @Test
    void strategy_acceptsAllValidStrategies() {
        RagRateLimitProperties props = new RagRateLimitProperties();

        props.setStrategy("ip");
        assertEquals("ip", props.getStrategy());

        props.setStrategy("api-key");
        assertEquals("api-key", props.getStrategy());

        props.setStrategy("user");
        assertEquals("user", props.getStrategy());

        props.setStrategy("");
        assertEquals("", props.getStrategy());
    }

    @Test
    void keyLimits_supportsMultipleEntries() {
        RagRateLimitProperties props = new RagRateLimitProperties();

        Map<String, Integer> keyLimits = new HashMap<>();
        keyLimits.put("sk-vip-1", 500);
        keyLimits.put("sk-vip-2", 500);
        keyLimits.put("sk-basic-1", 100);
        keyLimits.put("sk-basic-2", 100);
        props.setKeyLimits(keyLimits);

        assertEquals(4, props.getKeyLimits().size());
        assertEquals(500, props.getKeyLimits().get("sk-vip-1"));
        assertEquals(100, props.getKeyLimits().get("sk-basic-2"));
    }

    @Test
    void requestsPerMinute_acceptsLargeValues() {
        RagRateLimitProperties props = new RagRateLimitProperties();

        props.setRequestsPerMinute(10000);
        assertEquals(10000, props.getRequestsPerMinute());

        props.setRequestsPerMinute(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, props.getRequestsPerMinute());
    }
}
