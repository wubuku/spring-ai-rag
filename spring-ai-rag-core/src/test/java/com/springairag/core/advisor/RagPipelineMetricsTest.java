package com.springairag.core.advisor;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagPipelineMetricsTest {

    @Test
    void recordStep_shouldAddToList() {
        RagPipelineMetrics metrics = new RagPipelineMetrics();

        metrics.recordStep("QueryRewrite", 50, 3);

        assertEquals(1, metrics.getStepCount());
        assertEquals(1, metrics.getSteps().size());
        RagPipelineMetrics.StepMetric step = metrics.getSteps().get(0);
        assertEquals("QueryRewrite", step.stepName());
        assertEquals(50, step.durationMs());
        assertEquals(3, step.resultCount());
    }

    @Test
    void recordStep_multipleSteps_shouldPreserveOrder() {
        RagPipelineMetrics metrics = new RagPipelineMetrics();

        metrics.recordStep("QueryRewrite", 50, 3);
        metrics.recordStep("HybridSearch", 200, 10);
        metrics.recordStep("Rerank", 30, 5);

        assertEquals(3, metrics.getStepCount());
        assertEquals("QueryRewrite", metrics.getSteps().get(0).stepName());
        assertEquals("HybridSearch", metrics.getSteps().get(1).stepName());
        assertEquals("Rerank", metrics.getSteps().get(2).stepName());
    }

    @Test
    void getTotalDurationMs_shouldSumAllSteps() {
        RagPipelineMetrics metrics = new RagPipelineMetrics();

        metrics.recordStep("QueryRewrite", 50, 3);
        metrics.recordStep("HybridSearch", 200, 10);
        metrics.recordStep("Rerank", 30, 5);

        assertEquals(280, metrics.getTotalDurationMs());
    }

    @Test
    void getTotalDurationMs_empty_shouldReturnZero() {
        RagPipelineMetrics metrics = new RagPipelineMetrics();
        assertEquals(0, metrics.getTotalDurationMs());
    }

    @Test
    void getSteps_shouldReturnUnmodifiableList() {
        RagPipelineMetrics metrics = new RagPipelineMetrics();
        metrics.recordStep("Step1", 10, 1);

        assertThrows(UnsupportedOperationException.class, () ->
                metrics.getSteps().add(new RagPipelineMetrics.StepMetric("Hack", 0, 0)));
    }

    @Test
    void getOrCreate_newContext_shouldCreateAndStore() {
        Map<String, Object> context = new HashMap<>();

        RagPipelineMetrics metrics = RagPipelineMetrics.getOrCreate(context);

        assertNotNull(metrics);
        assertSame(metrics, context.get(RagPipelineMetrics.CONTEXT_KEY));
    }

    @Test
    void getOrCreate_existingMetrics_shouldReturnExisting() {
        Map<String, Object> context = new HashMap<>();
        RagPipelineMetrics first = RagPipelineMetrics.getOrCreate(context);

        RagPipelineMetrics second = RagPipelineMetrics.getOrCreate(context);

        assertSame(first, second);
    }

    @Test
    void get_existingMetrics_shouldReturnIt() {
        Map<String, Object> context = new HashMap<>();
        RagPipelineMetrics created = RagPipelineMetrics.getOrCreate(context);

        RagPipelineMetrics retrieved = RagPipelineMetrics.get(context);

        assertSame(created, retrieved);
    }

    @Test
    void get_nullContext_shouldReturnNull() {
        assertNull(RagPipelineMetrics.get(null));
    }

    @Test
    void get_emptyContext_shouldReturnNull() {
        assertNull(RagPipelineMetrics.get(new HashMap<>()));
    }

    @Test
    void getOrCreate_nullContext_shouldCreateNewWithoutStoring() {
        RagPipelineMetrics metrics = RagPipelineMetrics.getOrCreate(null);

        assertNotNull(metrics);
        assertEquals(0, metrics.getStepCount());
    }

    @Test
    void stepMetric_toString_shouldFormatCorrectly() {
        RagPipelineMetrics.StepMetric step = new RagPipelineMetrics.StepMetric("HybridSearch", 200, 10);

        assertEquals("HybridSearch[duration=200ms, results=10]", step.toString());
    }

    @Test
    void toString_shouldIncludeAllSteps() {
        RagPipelineMetrics metrics = new RagPipelineMetrics();
        metrics.recordStep("QueryRewrite", 50, 3);
        metrics.recordStep("HybridSearch", 200, 10);

        String str = metrics.toString();

        assertTrue(str.contains("QueryRewrite"));
        assertTrue(str.contains("HybridSearch"));
        assertTrue(str.contains("totalMs=250"));
    }
}
