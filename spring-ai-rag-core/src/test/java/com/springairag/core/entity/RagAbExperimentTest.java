package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagAbExperiment entity.
 */
class RagAbExperimentTest {

    @Test
    void defaultValues() {
        RagAbExperiment exp = new RagAbExperiment();
        assertEquals("DRAFT", exp.getStatus());
        assertEquals(100, exp.getMinSampleSize());
        assertNotNull(exp.getCreatedAt());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime start = now.plusDays(1);
        ZonedDateTime end = now.plusDays(7);

        RagAbExperiment exp = new RagAbExperiment();
        exp.setId(1L);
        exp.setExperimentName("Model A vs B");
        exp.setDescription("Compare DeepSeek vs Qwen responses");
        exp.setStatus("RUNNING");
        exp.setTrafficSplit(Map.of("model_a", 50.0, "model_b", 50.0));
        exp.setTargetMetric("response_latency_ms");
        exp.setStartTime(start);
        exp.setEndTime(end);
        exp.setMinSampleSize(500);
        exp.setMetadata(Map.of("collection_id", 1L, "query_count", 1000));
        exp.setCreatedAt(now);
        exp.setUpdatedAt(now);

        assertEquals(1L, exp.getId());
        assertEquals("Model A vs B", exp.getExperimentName());
        assertEquals("Compare DeepSeek vs Qwen responses", exp.getDescription());
        assertEquals("RUNNING", exp.getStatus());
        assertEquals("response_latency_ms", exp.getTargetMetric());
        assertEquals(start, exp.getStartTime());
        assertEquals(end, exp.getEndTime());
        assertEquals(500, exp.getMinSampleSize());
        assertEquals(now, exp.getCreatedAt());
        assertEquals(now, exp.getUpdatedAt());
    }

    @Test
    void trafficSplit_mapSerialization() {
        RagAbExperiment exp = new RagAbExperiment();
        Map<String, Double> split = Map.of("model_a", 70.0, "model_b", 30.0);
        exp.setTrafficSplit(split);

        assertEquals(70.0, exp.getTrafficSplit().get("model_a"));
        assertEquals(30.0, exp.getTrafficSplit().get("model_b"));
    }

    @Test
    void statusTransitions() {
        RagAbExperiment exp = new RagAbExperiment();
        assertEquals("DRAFT", exp.getStatus());

        exp.setStatus("RUNNING");
        assertEquals("RUNNING", exp.getStatus());

        exp.setStatus("PAUSED");
        assertEquals("PAUSED", exp.getStatus());

        exp.setStatus("COMPLETED");
        assertEquals("COMPLETED", exp.getStatus());
    }

    @Test
    void minSampleSize_customValue() {
        RagAbExperiment exp = new RagAbExperiment();
        exp.setMinSampleSize(200);
        assertEquals(200, exp.getMinSampleSize());

        exp.setMinSampleSize(1000);
        assertEquals(1000, exp.getMinSampleSize());
    }

    @Test
    void metadata_jsonMapSerialization() {
        RagAbExperiment exp = new RagAbExperiment();
        Map<String, Object> meta = Map.of(
                "collection_id", 1L,
                "user_count", 500,
                "config", Map.of("temperature", 0.7, "top_p", 0.9)
        );
        exp.setMetadata(meta);

        assertEquals(1L, exp.getMetadata().get("collection_id"));
        assertEquals(500, exp.getMetadata().get("user_count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) exp.getMetadata().get("config");
        assertEquals(0.7, config.get("temperature"));
    }

    @Test
    void optionalFields_canBeNull() {
        RagAbExperiment exp = new RagAbExperiment();
        exp.setDescription(null);
        exp.setTargetMetric(null);
        exp.setStartTime(null);
        exp.setEndTime(null);
        exp.setMetadata(null);
        exp.setUpdatedAt(null);

        assertNull(exp.getDescription());
        assertNull(exp.getTargetMetric());
        assertNull(exp.getStartTime());
        assertNull(exp.getEndTime());
        assertNull(exp.getMetadata());
        assertNull(exp.getUpdatedAt());
    }

    @Test
    void trafficSplit_doubleValues() {
        RagAbExperiment exp = new RagAbExperiment();
        exp.setTrafficSplit(Map.of("control", 0.0, "treatment", 100.0));
        assertEquals(0.0, exp.getTrafficSplit().get("control"));
        assertEquals(100.0, exp.getTrafficSplit().get("treatment"));
    }
}
