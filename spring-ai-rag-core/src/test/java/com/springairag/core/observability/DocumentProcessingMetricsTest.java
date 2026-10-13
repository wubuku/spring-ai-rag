package com.springairag.core.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentProcessingMetricsTest {

    @Test
    void recordsOnlyFiniteLowCardinalityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DocumentProcessingMetrics metrics = new DocumentProcessingMetrics(registry);

        metrics.recordRunTerminal("COMPLETED", "API", null);
        metrics.recordStageDuration("EMBEDDING", "SUCCEEDED",
                Duration.ofMillis(12));
        metrics.recordHousekeeping("terminal_sync");
        metrics.recordStuck(null);

        assertEquals(1.0, registry.counter(
                "rag.document-processing.runs",
                "status", "COMPLETED",
                "origin", "API",
                "terminal_reason", "NONE").count());
        assertNotNull(registry.timer(
                "rag.document-processing.stage.duration",
                "stage", "EMBEDDING",
                "result", "SUCCEEDED"));
        assertEquals(1.0, registry.counter(
                "rag.document-processing.housekeeping.reconciled",
                "action", "terminal_sync").count());
        assertEquals(1.0, registry.counter(
                "rag.document-processing.stuck",
                "stage", "UNKNOWN").count());
        assertNull(registry.find("rag.document-processing.runs")
                .tag("documentId", "42").counter());
    }

    @Test
    void rejectsNegativeStageDurationAndTracksActiveRuns() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DocumentProcessingMetrics metrics = new DocumentProcessingMetrics(registry);

        metrics.setActiveRuns(3);
        metrics.recordStageDuration("PARSING", "SUCCEEDED",
                Duration.ofSeconds(-1));

        assertEquals(3.0, registry.find(
                "rag.document-processing.active").gauge().value());
        assertEquals(0, registry.find("rag.document-processing.stage.duration")
                .timers().size());
    }
}
