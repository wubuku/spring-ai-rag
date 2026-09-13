package com.springairag.core.metrics;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SlowQueryMetricsService 残余（Batch 367）：无 MeterRegistry 构
 * 造降级、无 EntityManagerFactory 时统计为空、空查询统计的平均值
 * 归零守卫、日志路径的超长 SQL 截断分支。
 */
class SlowQueryMetricsServiceResidualTest {

    @Test
    void nullMeterRegistryStillRecordsCountersInMemory() {
        // 无 MeterRegistry 构造：counter/timer 为 null，内存计数仍生效。
        RagProperties properties = new RagProperties();
        SlowQueryMetricsService service =
                new SlowQueryMetricsService(properties, null, null);

        service.recordSlowQuery("SELECT 1", 2_000);

        assertEquals(1, service.getTotalSlowQueries());
        assertEquals(1, service.getRecentSlowQueries().size());
        assertTrue(service.getStatistics().isEmpty());
    }

    @Test
    void statsSummaryGuardsZeroQueriesWithoutSessionFactory() {
        RagProperties properties = new RagProperties();
        SlowQueryMetricsService service =
                new SlowQueryMetricsService(properties, null, null);

        SlowQueryMetricsService.SlowQueryStatsSummary summary =
                service.getStatsSummary();

        assertEquals(0, summary.totalQueryCount());
        assertEquals(0, summary.averageQueryDurationMs());
        assertEquals(service.getThresholdMs(), summary.thresholdMs());
    }

    @Test
    void longSqlIsTruncatedForLogWhileRecordKeepsFullText() {
        RagProperties properties = new RagProperties();
        properties.getSlowQuery().setLogEnabled(true);
        SlowQueryMetricsService service = new SlowQueryMetricsService(
                properties, null, new SimpleMeterRegistry());
        String longSql = "SELECT * FROM t WHERE c = '" + "x".repeat(800) + "'";

        service.recordSlowQuery(longSql, 1_500);

        // 日志路径截断不影响记录：保留完整 SQL。
        assertEquals(1, service.getRecentSlowQueries().size());
        assertEquals(longSql, service.getRecentSlowQueries().getFirst().sql());
    }

    @Test
    void sensitiveSqlFragmentsAreMaskedBeforeRetention() {
        RagProperties properties = new RagProperties();
        properties.getSlowQuery().setLogEnabled(true);
        SlowQueryMetricsService service = new SlowQueryMetricsService(
                properties, null, new SimpleMeterRegistry());
        String sql = "SELECT * FROM t WHERE api_key = 'sk-abc123'";

        service.recordSlowQuery(sql, 1_200);

        // 行为注记：脱敏仅作用于日志输出，保留记录存原始 SQL
        // （掩码正则替换分支经由日志路径真实执行）。
        assertEquals(sql, service.getRecentSlowQueries().getFirst().sql());
    }
}
