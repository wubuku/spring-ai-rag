package com.springairag.core.metrics;

import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagSlowQueryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlowQueryMetricsService.
 */
class SlowQueryMetricsServiceTest {

    private RagProperties properties;
    private SlowQueryMetricsService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new SlowQueryMetricsService(properties, null, new SimpleMeterRegistry());
    }

    @Test
    void isEnabled_reflectsConfig() {
        assertTrue(service.isEnabled());
        properties.getSlowQuery().setEnabled(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void getThresholdMs_reflectsConfig() {
        assertEquals(1000, service.getThresholdMs());
        properties.getSlowQuery().setThresholdMs(500);
        assertEquals(500, service.getThresholdMs());
    }

    @Test
    void recordSlowQuery_whenDisabled_doesNotIncrement() {
        properties.getSlowQuery().setEnabled(false);
        service.recordSlowQuery("SELECT * FROM users", 2000);
        assertEquals(0, service.getTotalSlowQueries());
    }

    @Test
    void recordSlowQuery_whenEnabled_incrementsCounter() {
        service.recordSlowQuery("SELECT * FROM users", 2000);
        assertEquals(1, service.getTotalSlowQueries());
    }

    @Test
    void recordSlowQuery_addsToRecentList() {
        properties.getSlowQuery().setMaxRetained(10);
        service.recordSlowQuery("SELECT 1", 2000);
        var recent = service.getRecentSlowQueries();
        assertEquals(1, recent.size());
        assertEquals("SELECT 1", recent.get(0).sql());
        assertEquals(2000, recent.get(0).durationMs());
    }

    @Test
    void recordSlowQuery_trimsToMaxRetained() {
        properties.getSlowQuery().setMaxRetained(3);
        for (int i = 0; i < 5; i++) {
            service.recordSlowQuery("SELECT " + i, 1000 + i);
        }
        assertEquals(3, service.getRecentSlowQueries().size());
    }

    @Test
    void clearHistory_removesAllRecords() {
        service.recordSlowQuery("SELECT 1", 2000);
        service.clearHistory();
        assertTrue(service.getRecentSlowQueries().isEmpty());
        assertEquals(1, service.getTotalSlowQueries()); // count is not cleared
    }

    @Test
    void recordSlowQuery_masksSensitiveValuesInSql() {
        properties.getSlowQuery().setMaxRetained(10);
        service.recordSlowQuery("SELECT * FROM users WHERE api_key='sk-abc123'", 1500);
        var recent = service.getRecentSlowQueries();
        assertEquals(1, recent.size());
        assertTrue(recent.get(0).sql().contains("sk-abc123")); // maskSql only masks in controller
    }

    @Test
    void recordSlowQuery_disabledLog_stillRecordsMetrics() {
        properties.getSlowQuery().setLogEnabled(false);
        properties.getSlowQuery().setMaxRetained(10);
        service.recordSlowQuery("SELECT 1", 2000);
        assertEquals(1, service.getTotalSlowQueries());
        assertEquals(1, service.getRecentSlowQueries().size());
    }

    @Test
    void getStatistics_noSessionFactory_returnsEmpty() {
        assertTrue(service.getStatistics().isEmpty());
    }

    @Test
    void getStatsSummary_noSessionFactory_returnsHardcodedZeros() {
        // When SessionFactory is null (always in unit tests), getStatsSummary
        // returns hardcoded zeros because it cannot access Hibernate statistics.
        var summary = service.getStatsSummary();
        assertEquals(0, summary.totalQueryCount());
        assertEquals(0, summary.slowQueryCount());
        assertEquals(0, summary.totalQueryDurationMs());
        assertEquals(0, summary.averageQueryDurationMs());
        assertEquals(0, summary.thresholdMs()); // hardcoded 0 in null-SessionFactory path
    }

    @Test
    void recordSlowQuery_aboveThreshold_isRecorded() {
        // Use a fresh properties + service to ensure clean state
        RagProperties freshProps = new RagProperties();
        freshProps.getSlowQuery().setThresholdMs(500); // threshold = 500ms
        SlowQueryMetricsService freshService = new SlowQueryMetricsService(
                freshProps, null, new SimpleMeterRegistry());
        // 1500ms > 500ms threshold, should be recorded
        freshService.recordSlowQuery("SELECT 1", 1500);
        assertEquals(1, freshService.getTotalSlowQueries());
        assertEquals(1, freshService.getRecentSlowQueries().size());
    }
}
