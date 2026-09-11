package com.springairag.core.metrics;

import com.springairag.core.config.RagProperties;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * getStatsSummary 的 Hibernate Statistics 映射：SessionFactory 可
 * 用时从 Hibernate 统计聚合查询执行数/最大耗时/平均耗时；不可用时
 * 返回归零快照。
 */
class SlowQueryMetricsStatsSummaryTest {

    private RagProperties properties;
    private EntityManagerFactory entityManagerFactory;
    private SlowQueryMetricsService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        entityManagerFactory = mock(EntityManagerFactory.class);
        service = new SlowQueryMetricsService(
                properties, entityManagerFactory,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private SessionFactory sessionFactoryWith(
            long queryCount, long maxDuration, String[] queries,
            long[] executionTotalTimes) {
        SessionFactory sf = mock(SessionFactory.class);
        Statistics stats = mock(Statistics.class);
        when(sf.getStatistics()).thenReturn(stats);
        when(stats.getQueryExecutionCount()).thenReturn(queryCount);
        when(stats.getQueryExecutionMaxTime()).thenReturn(maxDuration);
        Mockito.lenient().when(stats.getQueries()).thenReturn(queries);
        if (queries != null) {
            for (int i = 0; i < queries.length; i++) {
                org.hibernate.stat.QueryStatistics qs =
                        mock(org.hibernate.stat.QueryStatistics.class);
                Mockito.lenient().when(stats.getQueryStatistics(queries[i]))
                        .thenReturn(qs);
                long time = i < executionTotalTimes.length
                        ? executionTotalTimes[i] : 0;
                Mockito.lenient().when(qs.getExecutionTotalTime())
                        .thenReturn(time);
            }
        }
        return sf;
    }

    private void stubUnwrap(SessionFactory sf) {
        when(entityManagerFactory.unwrap(SessionFactory.class)).thenReturn(sf);
    }

    @Test
    void statsSummaryNullSessionFactoryReturnsZeroSnapshot() {
        var summary = service.getStatsSummary();

        assertEquals(0, summary.totalQueryCount());
        assertEquals(0, summary.totalQueryDurationMs());
        assertEquals(0, summary.slowQueryCount());
        assertEquals(1000, summary.thresholdMs());
        assertEquals(0, summary.averageQueryDurationMs());
        assertTrue(summary.recentSlowQueries().isEmpty());
    }

    @Test
    void statsSummaryWithHibernateDataReturnsAggregatedMetrics() {
        SessionFactory sf = sessionFactoryWith(
                10, 500, new String[]{"SELECT 1", "SELECT 2"}, new long[]{100, 200});
        stubUnwrap(sf);
        service.recordSlowQuery("SELECT 1", 600);

        var summary = service.getStatsSummary();

        assertEquals(10, summary.totalQueryCount());
        assertEquals(500, summary.totalQueryDurationMs());
        assertEquals(1, summary.slowQueryCount());
        // totalDurationMs = (100 + 200) / 1000.0 = 0.3ms → avg = 0 / 10 = 0
        assertEquals(0, summary.averageQueryDurationMs());
        assertNotNull(summary.recentSlowQueries());
    }

    @Test
    void statsSummaryWithSingleQueryComputesAvg() {
        SessionFactory sf = sessionFactoryWith(
                1, 100, new String[]{"SELECT 1"}, new long[]{2_000_000});
        stubUnwrap(sf);

        var summary = service.getStatsSummary();

        // executionTotalTime 2_000_000ns = 2ms, queryCount 1 → avg 2ms
        assertEquals(1, summary.totalQueryCount());
        // executionTotalTime 2_000_000ns = 2ms → avg = 2ms
        assertEquals(2, summary.averageQueryDurationMs());
    }
}
