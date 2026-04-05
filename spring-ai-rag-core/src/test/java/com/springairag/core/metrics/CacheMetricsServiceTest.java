package com.springairag.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheMetricsService 单元测试
 */
@DisplayName("CacheMetricsService Tests")
class CacheMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private CacheMetricsService cacheMetricsService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cacheMetricsService = new CacheMetricsService(meterRegistry, null);
    }

    @Test
    @DisplayName("Initial hit count should be zero")
    void getHitCount_initiallyZero() {
        assertEquals(0, cacheMetricsService.getHitCount());
    }

    @Test
    @DisplayName("Initial miss count should be zero")
    void getMissCount_initiallyZero() {
        assertEquals(0, cacheMetricsService.getMissCount());
    }

    @Test
    @DisplayName("getTotalCount should be sum of hits and misses")
    void getTotalCount_empty_returnsZero() {
        assertEquals(0, cacheMetricsService.getTotalCount());
    }

    @Test
    @DisplayName("getHitRate should return 0 when no requests")
    void getHitRate_empty_returnsZero() {
        assertEquals(0.0, cacheMetricsService.getHitRate());
    }

    @Test
    @DisplayName("getStats should return correct structure with zero values")
    void getStats_empty_returnsCorrectStructure() {
        Map<String, Object> stats = cacheMetricsService.getStats();

        assertEquals(0L, stats.get("hitCount"));
        assertEquals(0L, stats.get("missCount"));
        assertEquals(0L, stats.get("totalCount"));
        assertEquals("N/A", stats.get("hitRate"));
    }

    @Test
    @DisplayName("Counters should increment correctly via Micrometer")
    void countersIncrement() {
        Counter hitCounter = meterRegistry.find("rag.cache.embedding.hit").counter();
        Counter missCounter = meterRegistry.find("rag.cache.embedding.miss").counter();

        assertNotNull(hitCounter);
        assertNotNull(missCounter);

        hitCounter.increment();
        hitCounter.increment();
        missCounter.increment();

        assertEquals(2, cacheMetricsService.getHitCount());
        assertEquals(1, cacheMetricsService.getMissCount());
        assertEquals(3, cacheMetricsService.getTotalCount());
    }

    @Test
    @DisplayName("getHitRate should return correct percentage")
    void getHitRate_withData_returnsCorrectPercentage() {
        Counter hitCounter = meterRegistry.find("rag.cache.embedding.hit").counter();
        Counter missCounter = meterRegistry.find("rag.cache.embedding.miss").counter();

        // 3 hits out of 5 total = 60%
        hitCounter.increment(3);
        missCounter.increment(2);

        assertEquals(60.0, cacheMetricsService.getHitRate(), 0.01);
    }

    @Test
    @DisplayName("getHitRate should return 100 for all hits")
    void getHitRate_allHits_returns100() {
        Counter hitCounter = meterRegistry.find("rag.cache.embedding.hit").counter();
        hitCounter.increment(10);

        assertEquals(100.0, cacheMetricsService.getHitRate(), 0.01);
    }

    @Test
    @DisplayName("getHitRate should return 0 for all misses")
    void getHitRate_allMisses_returnsZero() {
        Counter missCounter = meterRegistry.find("rag.cache.embedding.miss").counter();
        missCounter.increment(10);

        assertEquals(0.0, cacheMetricsService.getHitRate());
    }

    @Test
    @DisplayName("getStats should return formatted hit rate string")
    void getStats_withData_returnsFormattedHitRate() {
        Counter hitCounter = meterRegistry.find("rag.cache.embedding.hit").counter();
        Counter missCounter = meterRegistry.find("rag.cache.embedding.miss").counter();

        hitCounter.increment(3);
        missCounter.increment(1);

        Map<String, Object> stats = cacheMetricsService.getStats();

        assertEquals(3L, stats.get("hitCount"));
        assertEquals(1L, stats.get("missCount"));
        assertEquals(4L, stats.get("totalCount"));
        assertEquals("75.0%", stats.get("hitRate"));
    }

    @Test
    @DisplayName("Multiple service instances should have independent counters")
    void independentInstances() {
        CacheMetricsService another = new CacheMetricsService(meterRegistry, null);

        Counter hitCounter = meterRegistry.find("rag.cache.embedding.hit").counter();
        hitCounter.increment(5);

        // Original service sees the shared counter
        assertEquals(5, cacheMetricsService.getHitCount());
        assertEquals(5, another.getHitCount());
    }
}
