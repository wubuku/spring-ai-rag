package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(description = "Slow query statistics from HikariCP connection pool")
public record SlowQueryStatsResponse(
        @Schema(description = "Whether slow query monitoring is enabled", example = "true") boolean enabled,
        @Schema(description = "Configured threshold in milliseconds", example = "1000") long thresholdMs,
        @Schema(description = "Total number of queries since startup", example = "12450") long totalQueryCount,
        @Schema(description = "Number of queries exceeding the threshold", example = "23") long slowQueryCount,
        @Schema(description = "Average query duration in milliseconds", example = "45") long averageDurationMs,
        @Schema(description = "Most recent slow query records") List<SlowQueryRecordDto> recentSlowQueries
) {
    /**
     * Individual slow query record.
     *
     * @param timestampMs when the query was executed (epoch ms)
     * @param durationMs how long the query took (ms)
     * @param sql the SQL query (sensitive values masked)
     */
    @Schema(description = "Individual slow query record")
    public record SlowQueryRecordDto(
            @Schema(description = "Epoch timestamp in milliseconds", example = "1712899200000") long timestampMs,
            @Schema(description = "Query duration in milliseconds", example = "1523") long durationMs,
            @Schema(description = "SQL query with sensitive values masked", example = "SELECT * FROM rag_documents WHERE id = ?") String sql
    ) {
        @Override
        public String toString() {
            return "SlowQueryRecordDto{" +
                    "timestampMs=" + timestampMs +
                    ", durationMs=" + durationMs +
                    ", sql='" + sql + '\'' +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "SlowQueryStatsResponse{" +
                "enabled=" + enabled +
                ", thresholdMs=" + thresholdMs +
                ", totalQueryCount=" + totalQueryCount +
                ", slowQueryCount=" + slowQueryCount +
                ", averageDurationMs=" + averageDurationMs +
                ", recentSlowQueries=" + (recentSlowQueries != null ? recentSlowQueries.size() + " query(ies)" : "null") +
                '}';
    }
}
