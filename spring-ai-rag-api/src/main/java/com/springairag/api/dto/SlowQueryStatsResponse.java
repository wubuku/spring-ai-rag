package com.springairag.api.dto;

import java.util.List;

/**
 * Slow query statistics response.
 *
 * @param enabled        whether slow query monitoring is enabled
 * @param thresholdMs    configured slow query threshold in milliseconds
 * @param totalQueryCount total number of queries executed since startup
 * @param slowQueryCount number of queries exceeding the threshold
 * @param averageDurationMs average query duration in milliseconds
 * @param recentSlowQueries list of recent slow query records
 */
public record SlowQueryStatsResponse(
        boolean enabled,
        long thresholdMs,
        long totalQueryCount,
        long slowQueryCount,
        long averageDurationMs,
        List<SlowQueryRecordDto> recentSlowQueries
) {
    /**
     * Individual slow query record.
     *
     * @param timestampMs when the query was executed (epoch ms)
     * @param durationMs how long the query took (ms)
     * @param sql the SQL query (sensitive values masked)
     */
    public record SlowQueryRecordDto(
            long timestampMs,
            long durationMs,
            String sql
    ) {}
}
