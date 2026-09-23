package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagAlert 实体访问器长尾（Batch 585，JaCoCo 驱动）：version、
 * dedupeKey、conditionState、stateVersion/notifiedVersion、updatedAt
 * 的读写与默认值。
 */
class RagAlertAccessorsTailTest {

    @Test
    void defaultsAreAppliedOnConstruction() {
        RagAlert alert = new RagAlert();

        assertEquals(0, alert.getStateVersion());
        assertEquals(0, alert.getNotifiedVersion());
        assertEquals("ACTIVE", alert.getStatus());
        assertNull(alert.getId());
        assertNull(alert.getDedupeKey());
        assertNull(alert.getConditionState());
        // updatedAt 构造时默认为当前时间，非 null。
        assertTrue(alert.getUpdatedAt() != null);
    }

    @Test
    void versionAndDedupeKeyRoundTrip() {
        RagAlert alert = new RagAlert();
        alert.setVersion(7L);
        alert.setDedupeKey("kb:cond:1");

        assertEquals(7L, alert.getVersion());
        assertEquals("kb:cond:1", alert.getDedupeKey());
    }

    @Test
    void stateAndNotifiedVersionsRoundTrip() {
        RagAlert alert = new RagAlert();
        alert.setStateVersion(3);
        alert.setNotifiedVersion(2);

        assertEquals(3, alert.getStateVersion());
        assertEquals(2, alert.getNotifiedVersion());
    }

    @Test
    void updatedAtRoundTrip() {
        RagAlert alert = new RagAlert();
        ZonedDateTime now = ZonedDateTime.now();
        alert.setUpdatedAt(now);

        assertEquals(now, alert.getUpdatedAt());
    }

    @Test
    void metricsMapRoundTrip() {
        RagAlert alert = new RagAlert();
        alert.setMetrics(Map.of("latency", 120));

        assertEquals(120, alert.getMetrics().get("latency"));
        assertTrue(alert.getMetrics() instanceof Map);
    }
}
