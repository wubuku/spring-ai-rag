package com.springairag.core.metrics;

import com.springairag.core.config.RagProperties;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SlowQueryMetricsService 缺省长尾（Batch 701，JaCoCo 驱动）：
 * 无 EntityManagerFactory 时统计为空、无查询记录时均值归零、
 * SQL 掩码与截断对 null 的容忍。
 *
 * 勿再投入：149-159 的 recordSlowQuery 回滚补偿要求
 * SlowQueryRecord 构造或 offer 抛出，但 sql 在入口已被
 * requireNonNull 拦截、队列为无界 Deque，经公共 API 不可达。
 */
class SlowQueryMetricsServiceNullTailTest {

    @Test
    void getStatisticsReturnsEmptyWithoutEntityManagerFactory() {
        var service = new SlowQueryMetricsService(
                new RagProperties(), null, null);

        Optional<Statistics> statistics = service.getStatistics();

        assertTrue(statistics.isEmpty());
    }

    @Test
    void getStatisticsExposesFactoryStatisticsWhenAvailable() {
        Statistics statistics = mock(Statistics.class);
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        when(sessionFactory.getStatistics()).thenReturn(statistics);
        when(emf.unwrap(SessionFactory.class)).thenReturn(sessionFactory);
        var service = new SlowQueryMetricsService(
                new RagProperties(), emf, null);

        Optional<Statistics> exposed = service.getStatistics();

        assertTrue(exposed.isPresent());
    }

    private SlowQueryMetricsService serviceWithMockedFactory() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        Statistics statistics = mock(Statistics.class);
        when(statistics.getQueryExecutionCount()).thenReturn(0L);
        when(statistics.getQueryExecutionMaxTime()).thenReturn(0L);
        when(statistics.getQueries()).thenReturn(null);
        when(sessionFactory.getStatistics()).thenReturn(statistics);
        when(emf.unwrap(SessionFactory.class)).thenReturn(sessionFactory);
        return new SlowQueryMetricsService(
                new RagProperties(), emf, null);
    }

    @Test
    void statsSummaryAveragesToZeroWithoutRecordedQueries() {
        var summary = serviceWithMockedFactory().getStatsSummary();

        assertEquals(0L, summary.totalQueryCount());
        assertEquals(0L, summary.averageQueryDurationMs());
        assertEquals(1000L, summary.thresholdMs());
    }

    private String invokePrivate(String name, Class<?>[] types,
                                 Object... args) throws Exception {
        Method method = SlowQueryMetricsService.class
                .getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (String) method.invoke(
                new SlowQueryMetricsService(new RagProperties(), null, null),
                args);
    }

    @Test
    void maskAndTruncateTolerateNullSql() throws Exception {
        assertNull(invokePrivate("maskSensitiveSql",
                new Class<?>[]{String.class}, (Object) null));
        assertNull(invokePrivate("truncateSql",
                new Class<?>[]{String.class, int.class},
                (Object) null, 100));
    }
}
