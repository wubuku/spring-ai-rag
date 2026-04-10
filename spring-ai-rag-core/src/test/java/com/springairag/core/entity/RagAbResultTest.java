package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagAbResult entity.
 */
class RagAbResultTest {

    @Test
    void defaultConstructor_works() {
        RagAbResult result = new RagAbResult();
        assertNotNull(result);
        assertNull(result.getId());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();

        RagAbExperiment experiment = new RagAbExperiment();
        experiment.setId(5L);

        RagAbResult result = new RagAbResult();
        result.setId(1L);
        result.setExperiment(experiment);
        result.setVariantName("control");
        result.setSessionId("sess-ab-123");
        result.setQuery("vector search optimization");
        result.setRetrievedDocumentIds("[201, 202, 203]");
        result.setMetrics(Map.of("latency_ms", 45.5, "relevance_score", 0.82));
        result.setIsConverted(true);
        result.setCreatedAt(now);

        assertEquals(1L, result.getId());
        assertEquals(experiment, result.getExperiment());
        assertEquals("control", result.getVariantName());
        assertEquals("sess-ab-123", result.getSessionId());
        assertEquals("vector search optimization", result.getQuery());
        assertEquals("[201, 202, 203]", result.getRetrievedDocumentIds());
        assertEquals(45.5, result.getMetrics().get("latency_ms"));
        assertEquals(0.82, result.getMetrics().get("relevance_score"));
        assertTrue(result.getIsConverted());
        assertEquals(now, result.getCreatedAt());
    }

    @Test
    void variantNames() {
        RagAbResult result = new RagAbResult();
        result.setVariantName("control");
        assertEquals("control", result.getVariantName());

        result.setVariantName("treatment-a");
        assertEquals("treatment-a", result.getVariantName());

        result.setVariantName("treatment-b");
        assertEquals("treatment-b", result.getVariantName());
    }

    @Test
    void conversionFlag() {
        RagAbResult result = new RagAbResult();
        assertNull(result.getIsConverted());

        result.setIsConverted(true);
        assertTrue(result.getIsConverted());

        result.setIsConverted(false);
        assertFalse(result.getIsConverted());
    }

    @Test
    void metrics_jsonMapWithDoubles() {
        RagAbResult result = new RagAbResult();
        Map<String, Double> metrics = Map.of(
                "p50_latency", 25.0,
                "p95_latency", 150.0,
                "p99_latency", 300.0,
                "relevance_score", 0.95
        );
        result.setMetrics(metrics);

        assertEquals(25.0, result.getMetrics().get("p50_latency"));
        assertEquals(300.0, result.getMetrics().get("p99_latency"));
    }

    @Test
    void optionalFields_canBeNull() {
        RagAbResult result = new RagAbResult();
        result.setRetrievedDocumentIds(null);
        result.setMetrics(null);
        result.setIsConverted(null);

        assertNull(result.getRetrievedDocumentIds());
        assertNull(result.getMetrics());
        assertNull(result.getIsConverted());
    }

    @Test
    void experiment_relationship() {
        RagAbExperiment exp1 = new RagAbExperiment();
        exp1.setId(10L);
        exp1.setExperimentName("exp-latency");

        RagAbExperiment exp2 = new RagAbExperiment();
        exp2.setId(20L);
        exp2.setExperimentName("exp-quality");

        RagAbResult result = new RagAbResult();
        result.setExperiment(exp1);
        assertEquals(exp1, result.getExperiment());
        assertEquals(10L, result.getExperiment().getId());

        result.setExperiment(exp2);
        assertEquals(exp2, result.getExperiment());
        assertEquals(20L, result.getExperiment().getId());
    }

    @Test
    void createdAt_defaultValueIsNotNull() {
        RagAbResult result = new RagAbResult();
        assertNotNull(result.getCreatedAt());
    }

    @Test
    void createdAt_canBeSet() {
        ZonedDateTime custom = ZonedDateTime.now().minusHours(1);
        RagAbResult result = new RagAbResult();
        result.setCreatedAt(custom);
        assertEquals(custom, result.getCreatedAt());
    }
}
