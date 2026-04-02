package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagSloConfig 实体单元测试
 */
class RagSloConfigTest {

    @Test
    @DisplayName("默认值和 getter/setter")
    void defaultsAndSetters() {
        var entity = new RagSloConfig();

        assertNull(entity.getId());
        assertTrue(entity.getEnabled()); // 默认 true
        assertNotNull(entity.getCreatedAt()); // 默认 ZonedDateTime.now()

        entity.setId(1L);
        entity.setSloName("latency-p95");
        entity.setSloType("LATENCY");
        entity.setTargetValue(500.0);
        entity.setUnit("ms");
        entity.setDescription("P95 延迟 < 500ms");
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
        assertEquals("P95 延迟 < 500ms", entity.getDescription());
        assertFalse(entity.getEnabled());
        assertEquals("high", entity.getMetadata().get("severity"));
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }
}
