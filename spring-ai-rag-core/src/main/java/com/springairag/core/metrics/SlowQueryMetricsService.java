package com.springairag.core.metrics;

import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slow query monitoring service using Hibernate statistics.
 *
 * <p>Tracks queries exceeding the configured threshold and exposes:
 * <ul>
 *   <li>Slow query count (counter)</li>
 *   <li>Slowest query durations (timer)</li>
 *   <li>Top N slowest queries (REST endpoint)</li>
 * </ul>
 *
 * <p>Requires {@code spring.jpa.properties.hibernate.generate_statistics=true} to be set.
 */
@Service
public class SlowQueryMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryMetricsService.class);

    private final RagProperties properties;
    private final EntityManagerFactory entityManagerFactory;
    private final Counter slowQueryCounter;
    private final Timer slowestQueryTimer;
    private final Queue<SlowQueryRecord> recentSlowQueries = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalSlowQueries = new AtomicLong(0);
    private volatile SessionFactory sessionFactory;

    public SlowQueryMetricsService(
            @Autowired RagProperties properties,
            @Autowired(required = false) EntityManagerFactory entityManagerFactory,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.entityManagerFactory = entityManagerFactory;
        if (meterRegistry != null) {
            this.slowQueryCounter = meterRegistry.counter("rag.slow_query.total");
            this.slowestQueryTimer = meterRegistry.timer("rag.slow_query.duration");
            meterRegistry.gauge("rag.slow_query.count", totalSlowQueries);
        } else {
            this.slowQueryCounter = null;
            this.slowestQueryTimer = null;
        }
    }

    private synchronized SessionFactory getSessionFactory() {
        if (sessionFactory == null && entityManagerFactory != null) {
            sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            if (sessionFactory != null) {
                sessionFactory.getStatistics().setStatisticsEnabled(true);
                log.info("Hibernate statistics enabled for slow query monitoring");
            }
        }
        return sessionFactory;
    }

    /**
     * Check if slow query monitoring is enabled.
     */
    public boolean isEnabled() {
        return properties.getSlowQuery().isEnabled();
    }

    /**
     * Get the configured slow query threshold in milliseconds.
     */
    public long getThresholdMs() {
        return properties.getSlowQuery().getThresholdMs();
    }

    /**
     * Get the total number of slow queries since startup.
     */
    public long getTotalSlowQueries() {
        return totalSlowQueries.get();
    }

    /**
     * Get recent slow query records (up to maxRetained).
     */
    public List<SlowQueryRecord> getRecentSlowQueries() {
        return new ArrayList<>(recentSlowQueries);
    }

    /**
     * Clear slow query history.
     */
    public void clearHistory() {
        recentSlowQueries.clear();
    }

    /**
     * Record a slow query event.
     *
     * @param sql       SQL query string
     * @param durationMs execution time in milliseconds
     */
    public void recordSlowQuery(String sql, long durationMs) {
        if (!isEnabled()) {
            return;
        }

        totalSlowQueries.incrementAndGet();
        if (slowQueryCounter != null) {
            slowQueryCounter.increment();
        }
        if (slowestQueryTimer != null) {
            slowestQueryTimer.record(java.time.Duration.ofMillis(durationMs));
        }

        if (properties.getSlowQuery().isLogEnabled()) {
            String maskedSql = maskSensitiveSql(sql);
            log.warn("Slow query detected ({}ms > {}ms threshold): {}",
                    durationMs, getThresholdMs(), truncateSql(maskedSql, 500));
        }

        int maxRetained = properties.getSlowQuery().getMaxRetained();
        if (maxRetained > 0) {
            SlowQueryRecord record = new SlowQueryRecord(
                    System.currentTimeMillis(), sql, durationMs);
            recentSlowQueries.offer(record);
            // Trim to maxRetained
            while (recentSlowQueries.size() > maxRetained) {
                recentSlowQueries.poll();
            }
        }
    }

    /**
     * Get current Hibernate query statistics.
     */
    public Optional<Statistics> getStatistics() {
        SessionFactory sf = getSessionFactory();
        if (sf == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sf.getStatistics());
    }

    /**
     * Get aggregated query statistics summary.
     */
    public SlowQueryStatsSummary getStatsSummary() {
        SessionFactory sf = getSessionFactory();
        if (sf == null) {
            return new SlowQueryStatsSummary(0, 0, 0, 0, 0, Collections.emptyList());
        }

        Statistics stats = sf.getStatistics();
        long queryCount = stats.getQueryExecutionCount();
        long maxDuration = stats.getQueryExecutionMaxTime();
        long slowCount = totalSlowQueries.get();

        // Calculate total and average from individual query statistics
        // Hibernate stores durations in nanoseconds
        double totalDurationMs = 0;
        String[] queries = stats.getQueries();
        if (queries != null) {
            for (String query : queries) {
                org.hibernate.stat.QueryStatistics qs = stats.getQueryStatistics(query);
                if (qs != null) {
                    totalDurationMs += qs.getExecutionTotalTime() / 1_000_000.0;
                }
            }
        }
        long avgDurationMs = queryCount > 0 ? (long) (totalDurationMs / queryCount) : 0;

        return new SlowQueryStatsSummary(
                queryCount,
                maxDuration,
                slowCount,
                getThresholdMs(),
                avgDurationMs,
                getRecentSlowQueries()
        );
    }

    private String maskSensitiveSql(String sql) {
        if (sql == null) return null;
        // Mask obvious API keys and tokens in SQL comments/values
        return sql.replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*=\\s*'[^']*'",
                "$1='***'");
    }

    private String truncateSql(String sql, int maxLength) {
        if (sql == null) return null;
        return sql.length() <= maxLength ? sql : sql.substring(0, maxLength) + "...";
    }

    /** Record of a single slow query event. */
    public record SlowQueryRecord(
            /** Timestamp when the query was recorded (epoch ms). */
            long timestampMs,
            /** SQL query string. */
            String sql,
            /** Execution time in milliseconds. */
            long durationMs
    ) {}

    /** Aggregated slow query statistics summary. */
    public record SlowQueryStatsSummary(
            long totalQueryCount,
            long totalQueryDurationMs,
            long slowQueryCount,
            long thresholdMs,
            long averageQueryDurationMs,
            List<SlowQueryRecord> recentSlowQueries
    ) {}
}
