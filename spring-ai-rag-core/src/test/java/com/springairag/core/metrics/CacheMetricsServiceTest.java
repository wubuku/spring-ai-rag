package com.springairag.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheMetricsService 测试
 */
class CacheMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private CacheMetricsService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CacheMetricsService(meterRegistry);
    }

    @Test
    void getHitCount_initialZero() {
        assertEquals(0, service.getHitCount());
    }

    @Test
    void getMissCount_initialZero() {
        assertEquals(0, service.getMissCount());
    }

    @Test
    void getTotalCount_initialZero() {
        assertEquals(0, service.getTotalCount());
    }

    @Test
    void getHitRate_noData_returnsZero() {
        assertEquals(0.0, service.getHitRate());
    }

    @Test
    void getHitCount_incrementsFromSharedCounter() {
        Counter hitCounter = meterRegistry.counter("rag.cache.embedding.hit");
        hitCounter.increment(5);
        assertEquals(5, service.getHitCount());
    }

    @Test
    void getMissCount_incrementsFromSharedCounter() {
        Counter missCounter = meterRegistry.counter("rag.cache.embedding.miss");
        missCounter.increment(3);
        assertEquals(3, service.getMissCount());
    }

    @Test
    void getTotalCount_sumsHitAndMiss() {
        meterRegistry.counter("rag.cache.embedding.hit").increment(7);
        meterRegistry.counter("rag.cache.embedding.miss").increment(3);
        assertEquals(10, service.getTotalCount());
    }

    @Test
    void getHitRate_calculatesPercentage() {
        meterRegistry.counter("rag.cache.embedding.hit").increment(7);
        meterRegistry.counter("rag.cache.embedding.miss").increment(3);
        assertEquals(70.0, service.getHitRate());
    }

    @Test
    void getHitRate_allHits_returns100() {
        meterRegistry.counter("rag.cache.embedding.hit").increment(10);
        assertEquals(100.0, service.getHitRate());
    }

    @Test
    void getHitRate_allMisses_returns0() {
        meterRegistry.counter("rag.cache.embedding.miss").increment(10);
        assertEquals(0.0, service.getHitRate());
    }

    @Test
    void getStats_returnsCompleteMap() {
        meterRegistry.counter("rag.cache.embedding.hit").increment(8);
        meterRegistry.counter("rag.cache.embedding.miss").increment(2);

        Map<String, Object> stats = service.getStats();
        assertEquals(8L, stats.get("hitCount"));
        assertEquals(2L, stats.get("missCount"));
        assertEquals(10L, stats.get("totalCount"));
        assertEquals("80.0%", stats.get("hitRate"));
    }

    @Test
    void getStats_noData_returnsNA() {
        Map<String, Object> stats = service.getStats();
        assertEquals("N/A", stats.get("hitRate"));
    }

    @Test
    void getStats_containsAllKeys() {
        Map<String, Object> stats = service.getStats();
        assertTrue(stats.containsKey("hitCount"));
        assertTrue(stats.containsKey("missCount"));
        assertTrue(stats.containsKey("totalCount"));
        assertTrue(stats.containsKey("hitRate"));
    }
}
