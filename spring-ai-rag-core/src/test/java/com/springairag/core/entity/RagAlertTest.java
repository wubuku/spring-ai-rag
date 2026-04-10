package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagAlert entity.
 */
class RagAlertTest {

    @Test
    void defaultValues_statusIsActive() {
        RagAlert alert = new RagAlert();
        assertEquals("ACTIVE", alert.getStatus());
    }

    @Test
    void defaultValues_createdAtIsNotNull() {
        RagAlert alert = new RagAlert();
        assertNotNull(alert.getCreatedAt());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime resolved = now.plusHours(1);
        ZonedDateTime silenced = now.plusMinutes(30);

        RagAlert alert = new RagAlert();
        alert.setId(1L);
        alert.setAlertType("THRESHOLD_HIGH");
        alert.setAlertName("P99 Latency");
        alert.setMessage("P99 latency exceeds 1000ms threshold");
        alert.setSeverity("CRITICAL");
        alert.setMetrics(Map.of("p99_ms", 2500, "threshold_ms", 1000, "endpoint", "/api/search"));
        alert.setStatus("RESOLVED");
        alert.setResolution("SLO threshold increased to 2000ms");
        alert.setFiredAt(now);
        alert.setResolvedAt(resolved);
        alert.setSilencedUntil(silenced);
        alert.setCreatedAt(now);

        assertEquals(1L, alert.getId());
        assertEquals("THRESHOLD_HIGH", alert.getAlertType());
        assertEquals("P99 Latency", alert.getAlertName());
        assertEquals("P99 latency exceeds 1000ms threshold", alert.getMessage());
        assertEquals("CRITICAL", alert.getSeverity());
        assertEquals("RESOLVED", alert.getStatus());
        assertEquals("SLO threshold increased to 2000ms", alert.getResolution());
        assertEquals(now, alert.getFiredAt());
        assertEquals(resolved, alert.getResolvedAt());
        assertEquals(silenced, alert.getSilencedUntil());
        assertEquals(now, alert.getCreatedAt());
    }

    @Test
    void metrics_jsonMapSerialization() {
        RagAlert alert = new RagAlert();
        Map<String, Object> metrics = Map.of(
                "p50_ms", 500,
                "p95_ms", 1500,
                "p99_ms", 2500,
                "count", 10000,
                "dimensions", Map.of("model", "bge-m3", "collection", "docs")
        );
        alert.setMetrics(metrics);

        assertEquals(500, alert.getMetrics().get("p50_ms"));
        assertEquals(2500, alert.getMetrics().get("p99_ms"));
        assertNotNull(alert.getMetrics().get("dimensions"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) alert.getMetrics().get("dimensions");
        assertEquals("bge-m3", dims.get("model"));
    }

    @Test
    void statusTransitions() {
        RagAlert alert = new RagAlert();
        assertEquals("ACTIVE", alert.getStatus());

        alert.setStatus("RESOLVED");
        assertEquals("RESOLVED", alert.getStatus());

        alert.setStatus("SILENCED");
        assertEquals("SILENCED", alert.getStatus());
    }

    @Test
    void alertTypes() {
        RagAlert alert = new RagAlert();
        alert.setAlertType("THRESHOLD_HIGH");
        assertEquals("THRESHOLD_HIGH", alert.getAlertType());

        alert.setAlertType("SLO_BREACH");
        assertEquals("SLO_BREACH", alert.getAlertType());

        alert.setAlertType("AVAILABILITY");
        assertEquals("AVAILABILITY", alert.getAlertType());
    }

    @Test
    void severityLevels() {
        RagAlert alert = new RagAlert();

        alert.setSeverity("INFO");
        assertEquals("INFO", alert.getSeverity());

        alert.setSeverity("WARNING");
        assertEquals("WARNING", alert.getSeverity());

        alert.setSeverity("CRITICAL");
        assertEquals("CRITICAL", alert.getSeverity());
    }

    @Test
    void nullMetrics_handledGracefully() {
        RagAlert alert = new RagAlert();
        alert.setMetrics(null);
        assertNull(alert.getMetrics());
    }

    @Test
    void optionalFields_canBeNull() {
        RagAlert alert = new RagAlert();
        alert.setResolution(null);
        alert.setResolvedAt(null);
        alert.setSilencedUntil(null);

        assertNull(alert.getResolution());
        assertNull(alert.getResolvedAt());
        assertNull(alert.getSilencedUntil());
    }
}
