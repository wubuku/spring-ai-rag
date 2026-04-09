package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagSloConfig Entity Unit Test
 */
class RagSloConfigTest {

    @Test
    @DisplayName("Default values and getters/setters")
    void defaultsAndSetters() {
        var entity = new RagSloConfig();

        assertNull(entity.getId());
        assertTrue(entity.getEnabled()); // defaults to true
        assertNotNull(entity.getCreatedAt()); // defaults to ZonedDateTime.now()

        entity.setId(1L);
        entity.setSloName("latency-p95");
        entity.setSloType("LATENCY");
        entity.setTargetValue(500.0);
        entity.setUnit("ms");
        entity.setDescription("P95 latency < 500ms");
        entity.setEnabled(false);
        entity.setMetadata(Map.of("severity", "high"));
        ZonedDateTime now = ZonedDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertEquals(1L, entity.getId());
        assertEquals("latency-p95", entity.getSloName());
        assertEquals("LATENCY", entity.getSloType());
        assertEquals(500.0, entity.getTargetValue());
        assertEquals("ms", entity.getUnit());
        assertEquals("P95 latency < 500ms", entity.getDescription());
        assertFalse(entity.getEnabled());
        assertEquals("high", entity.getMetadata().get("severity"));
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }
}
