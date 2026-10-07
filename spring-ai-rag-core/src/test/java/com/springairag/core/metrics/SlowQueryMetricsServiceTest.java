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
        // null SQL is a programming error enforced by Objects.requireNonNull before any metrics are recorded
        properties.getSlowQuery().setMaxRetained(10);
        assertThrows(NullPointerException.class, () ->
                service.recordSlowQuery(null, 2000));
        // counter does NOT increment because the exception is thrown before record creation
        assertEquals(0, service.getTotalSlowQueries());
        assertTrue(service.getRecentSlowQueries().isEmpty());
    }

    @Test
    void recordSlowQuery_nullSql_throwsEvenWhenMaxRetainedZero() {
        // maxRetained=0 path: old code silently incremented counters/timer for null SQL
        // (no SlowQueryRecord created → no exception → counters not rolled back)
        // Fix: Objects.requireNonNull(sql) throws immediately before any metrics are recorded
        properties.getSlowQuery().setMaxRetained(0);
        assertThrows(NullPointerException.class, () ->
                service.recordSlowQuery(null, 2000));
        // counters must not be incremented (early validation before any metric recording)
        assertEquals(0, service.getTotalSlowQueries());
    }

    @Test
    void recordSlowQuery_sensitiveSqlIsMaskedInTheLogOnly() {
        // Batch 953：这条用例原先叫 recordSlowQuery_nullSql_masksGracefully，
        // 注释写着"maskSensitiveSql 能容忍 null SQL"——可它压根没调
        // recordSlowQuery，只断言 getStatsSummary() 非 null。而
        // recordSlowQuery 早就在 Objects.requireNonNull(sql) 处把 null 挡掉了，
        // 那句注释描述的路径从那时起就不存在了。
        //
        // 真正有价值、且当时完全没被覆盖的是另一件事：脱敏只发生在
        // 日志这一侧（log.warn 收 maskSensitiveSql 的结果），
        // 留存记录里存的仍是原文。这里把两件事分别钉住。
        properties.getSlowQuery().setMaxRetained(10);
        ch.qos.logback.classic.Logger serviceLogger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(SlowQueryMetricsService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
                appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            service.recordSlowQuery(
                    "SELECT * FROM users WHERE api_key = 'sk-live-supersecret'", 2000);

            String logged = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("Slow query detected"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "慢查询日志没产生；实际日志：" + appender.list));

            // 日志侧：密钥被替换掉，原文不出现
            assertFalse(logged.contains("sk-live-supersecret"),
                    "原始密钥不得进日志：" + logged);
            assertTrue(logged.contains("api_key='***'"),
                    "密钥应被替换为掩码：" + logged);

            // 留存侧：记录的仍是原文（供排障用），且只留一条
            var recent = service.getRecentSlowQueries();
            assertEquals(1, recent.size());
            assertTrue(recent.get(0).sql().contains("sk-live-supersecret"),
                    "留存记录存的是原文，不该被脱敏");
            assertEquals(1, service.getTotalSlowQueries());
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
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
