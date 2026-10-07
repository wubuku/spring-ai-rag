package com.springairag.core.advisor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
        // Batch 954：原来这里是 8 条 assertNotNull(getXxx())。名字写着
        // "creates ALL"，可逐个非空既抓不到"多注册了一个"也抓不到
        // "某个注册到了别的名字上"——getter 非空并不说明名字对。
        // 改成拿注册表的全量名字集合做双向比对。
        assertEquals(
                Set.of(
                        "rag.advisor.query_rewrite.duration",
                        "rag.advisor.hybrid_search.duration",
                        "rag.advisor.rerank.duration",
                        // 三个 timer 都 publishPercentiles(0.5, 0.95, 0.99)，
                        // Micrometer 会为每个再注册一个辅助 meter。
                        // 这三条第一版漏了，是这条断言自己先红才暴露的。
                        "rag.advisor.query_rewrite.duration.percentile",
                        "rag.advisor.hybrid_search.duration.percentile",
                        "rag.advisor.rerank.duration.percentile",
                        "rag.advisor.query_rewrite.count",
                        "rag.advisor.hybrid_search.count",
                        "rag.advisor.hybrid_search.results",
                        "rag.advisor.rerank.count",
                        "rag.advisor.rerank.skipped"),
                registeredMeterNames(),
                "init() 注册的指标名集合与预期不符");
    }

    /** 初始化后注册表里应当只有这 8 个 meter，一个不多一个不少。 */
    private Set<String> registeredMeterNames() {
        return meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    @Test
    void record_nullStepName_isIgnored() {
        advisorMetrics.record(null, 100, 5);

        // No meters updated for null step name
        assertEquals(0.0, meterRegistry.find("rag.advisor.query_rewrite.duration").timer().count());
        assertEquals(0.0, meterRegistry.find("rag.advisor.hybrid_search.duration").timer().count());
        assertEquals(0.0, meterRegistry.find("rag.advisor.rerank.duration").timer().count());
    }

    @Test
    void record_emptyStepName_isIgnored() {
        advisorMetrics.record("", 100, 5);

        // No meters updated for empty step name
        assertEquals(0.0, meterRegistry.find("rag.advisor.query_rewrite.duration").timer().count());
        assertEquals(0.0, meterRegistry.find("rag.advisor.hybrid_search.duration").timer().count());
        assertEquals(0.0, meterRegistry.find("rag.advisor.rerank.duration").timer().count());
    }

    @Test
    void record_zeroDuration_isRecorded() {
        advisorMetrics.record("QueryRewrite", 0, 0);

        Timer timer = meterRegistry.find("rag.advisor.query_rewrite.duration").timer();
        assertEquals(1, timer.count());
        assertEquals(0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(1.0, advisorMetrics.getQueryRewriteCount().count());
    }
}
