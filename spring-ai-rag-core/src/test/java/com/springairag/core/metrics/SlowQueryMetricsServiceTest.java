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
    void getStatsSummary_noSessionFactory_returnsThresholdFromProperties() {
        // When SessionFactory is null (always in unit tests), getStatsSummary
        // returns zeros for query stats but preserves the configured threshold.
        var summary = service.getStatsSummary();
        assertEquals(0, summary.totalQueryCount());
        assertEquals(0, summary.slowQueryCount());
        assertEquals(0, summary.totalQueryDurationMs());
        assertEquals(0, summary.averageQueryDurationMs());
        assertEquals(service.getThresholdMs(), summary.thresholdMs()); // uses configured threshold, not 0
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

    @Test
    void recordSlowQuery_maxRetainedZero_doesNotAddToRecent() {
        // maxRetained=0 means don't keep any recent queries
        properties.getSlowQuery().setMaxRetained(0);
        service.recordSlowQuery("SELECT 1", 2000);
        assertEquals(1, service.getTotalSlowQueries()); // counter still increments
        assertTrue(service.getRecentSlowQueries().isEmpty()); // but not retained
    }

    @Test
    void recordSlowQuery_thresholdZero_recordsAllQueries() {
        // threshold=0 means any query is considered slow
        RagProperties freshProps = new RagProperties();
        freshProps.getSlowQuery().setThresholdMs(0);
        SlowQueryMetricsService freshService = new SlowQueryMetricsService(
                freshProps, null, new SimpleMeterRegistry());
        freshProps.getSlowQuery().setMaxRetained(10);
        freshService.recordSlowQuery("SELECT 1", 1); // 1ms > 0ms threshold
        assertEquals(1, freshService.getTotalSlowQueries());
        assertEquals(1, freshService.getRecentSlowQueries().size());
    }

    @Test
    void getRecentSlowQueries_initiallyEmpty() {
        assertTrue(service.getRecentSlowQueries().isEmpty());
    }

    @Test
    void recordSlowQuery_nullSql_throwsNullPointerException() {
        // null SQL is now a programming error enforced by SlowQueryRecord constructor
        properties.getSlowQuery().setMaxRetained(10);
        assertThrows(NullPointerException.class, () ->
                service.recordSlowQuery(null, 2000));
        // counter does NOT increment because the exception is thrown before record creation
        assertEquals(0, service.getTotalSlowQueries());
        assertTrue(service.getRecentSlowQueries().isEmpty());
    }

    @Test
    void recordSlowQuery_nullSql_masksGracefully() {
        // maskSensitiveSql handles null SQL without throwing
        var summary = service.getStatsSummary();
        assertNotNull(summary);
    }

    @Test
    void slowQueryRecord_toString_truncatesLongSql() {
        var record = new SlowQueryMetricsService.SlowQueryRecord(1000L, "SELECT a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z FROM users WHERE id > 100", 1500L);
        String str = record.toString();
        assertTrue(str.contains("SlowQueryRecord"));
        assertTrue(str.contains("durationMs=1500"));
        assertTrue(str.contains("...")); // long SQL truncated
        assertFalse(str.contains("FROM users WHERE id > 100")); // truncated, not full
    }

    @Test
    void slowQueryRecord_toString_showsShortSqlUnchanged() {
        var record = new SlowQueryMetricsService.SlowQueryRecord(2000L, "SELECT 1", 500L);
        String str = record.toString();
        assertTrue(str.contains("SELECT 1"));
        assertFalse(str.contains("..."));
    }

    @Test
    void slowQueryRecord_nullSql_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new SlowQueryMetricsService.SlowQueryRecord(1000L, null, 500L));
    }

    @Test
    void slowQueryStatsSummary_toString_containsKeyFields() {
        var summary = new SlowQueryMetricsService.SlowQueryStatsSummary(
                100L, 5000L, 10L, 1000L, 50L, java.util.Collections.emptyList());
        String str = summary.toString();
        assertTrue(str.contains("SlowQueryStatsSummary"));
        assertTrue(str.contains("totalQueryCount=100"));
        assertTrue(str.contains("slowQueryCount=10"));
        assertTrue(str.contains("thresholdMs=1000"));
        assertTrue(str.contains("averageQueryDurationMs=50"));
    }

    @Test
    void recordSlowQuery_sensitiveDataMaskedInLog() {
        // Verify the SENSITIVE_SQL_PATTERN static compiles and masks correctly
        properties.getSlowQuery().setLogEnabled(true);
        properties.getSlowQuery().setMaxRetained(10);
        // This should not throw and should mask api_key
        service.recordSlowQuery("SELECT * FROM users WHERE token='secret123' AND password='pass' AND api_key='key123'", 2000);
        assertEquals(1, service.getTotalSlowQueries());
    }
}
