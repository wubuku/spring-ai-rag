package com.springairag.core.advisor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdvisorMetrics unit tests — verifies Micrometer meter creation and recording behavior.
 */
class AdvisorMetricsTest {

    private MeterRegistry meterRegistry;
    private AdvisorMetrics advisorMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        advisorMetrics = new AdvisorMetrics(meterRegistry);
        advisorMetrics.init();
    }

    @Test
    void init_createsAllTimersAndCounters() {
        assertNotNull(advisorMetrics.getQueryRewriteTimer());
        assertNotNull(advisorMetrics.getHybridSearchTimer());
        assertNotNull(advisorMetrics.getRerankTimer());
        assertNotNull(advisorMetrics.getQueryRewriteCount());
        assertNotNull(advisorMetrics.getHybridSearchCount());
        assertNotNull(advisorMetrics.getHybridSearchResultsCount());
        assertNotNull(advisorMetrics.getRerankCount());
        assertNotNull(advisorMetrics.getRerankSkippedCount());
    }

    @Test
    void record_queryRewrite_incrementsTimerAndCounter() {
        advisorMetrics.record("QueryRewrite", 50, 3);

        Timer timer = meterRegistry.find("rag.advisor.query_rewrite.duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(50, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));

        assertEquals(1.0, advisorMetrics.getQueryRewriteCount().count());
    }

    @Test
    void record_hybridSearch_incrementsTimerAndCounters() {
        advisorMetrics.record("HybridSearch", 120, 10);

        Timer timer = meterRegistry.find("rag.advisor.hybrid_search.duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(120, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));

        assertEquals(1.0, advisorMetrics.getHybridSearchCount().count());
        assertEquals(10.0, advisorMetrics.getHybridSearchResultsCount().count());
    }

    @Test
    void record_rerank_incrementsTimerAndCounter() {
        advisorMetrics.record("Rerank", 80, 5);

        Timer timer = meterRegistry.find("rag.advisor.rerank.duration").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(80, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));

        assertEquals(1.0, advisorMetrics.getRerankCount().count());
        assertEquals(0.0, advisorMetrics.getRerankSkippedCount().count());
    }

    @Test
    void record_rerankWithZeroResults_incrementsSkippedCounter() {
        advisorMetrics.record("Rerank", 10, 0);

        assertEquals(1.0, advisorMetrics.getRerankCount().count());
        assertEquals(1.0, advisorMetrics.getRerankSkippedCount().count());
    }

    @Test
    void record_unknownStep_doesNothing() {
        advisorMetrics.record("UnknownStep", 100, 5);

        // No meters should be updated for unknown step
        assertEquals(0.0, meterRegistry.find("rag.advisor.query_rewrite.duration").timer().count());
        assertEquals(0.0, meterRegistry.find("rag.advisor.hybrid_search.duration").timer().count());
        assertEquals(0.0, meterRegistry.find("rag.advisor.rerank.duration").timer().count());
    }

    @Test
    void record_multipleInvocations_accumulatesCorrectly() {
        advisorMetrics.record("QueryRewrite", 10, 2);
        advisorMetrics.record("QueryRewrite", 20, 3);
        advisorMetrics.record("HybridSearch", 30, 5);

        Timer qrTimer = meterRegistry.find("rag.advisor.query_rewrite.duration").timer();
        assertEquals(2, qrTimer.count());
        assertEquals(30, qrTimer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));

        assertEquals(2.0, advisorMetrics.getQueryRewriteCount().count());
        assertEquals(1.0, advisorMetrics.getHybridSearchCount().count());
        assertEquals(5.0, advisorMetrics.getHybridSearchResultsCount().count());
    }
}
