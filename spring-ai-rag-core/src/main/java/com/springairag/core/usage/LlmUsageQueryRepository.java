package com.springairag.core.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only aggregation boundary for the durable model-invocation ledger.
 */
@Repository
public class LlmUsageQueryRepository {

    private static final String SELECT_COLUMNS = """
            COUNT(DISTINCT logical_execution_id) AS logical_execution_count,
            COUNT(*) AS invocation_count,
            COUNT(*) FILTER (WHERE outcome = 'SUCCEEDED') AS succeeded_count,
            COUNT(*) FILTER (WHERE outcome = 'FAILED') AS failed_count,
            COUNT(*) FILTER (WHERE outcome = 'CANCELLED') AS cancelled_count,
            COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
            COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
            COALESCE(SUM(total_tokens), 0) AS total_tokens,
            COUNT(*) FILTER (WHERE usage_available) AS usage_available_count,
            COUNT(*) FILTER (WHERE NOT usage_available) AS usage_unavailable_count,
            COUNT(*) FILTER (WHERE NOT pricing_available) AS pricing_unavailable_count,
            COUNT(*) FILTER (WHERE NOT cost_available) AS cost_unavailable_count
            """;

    private static final String WHERE_PREFIX = """
            WHERE started_at >= ? AND started_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public LlmUsageQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UsageAggregate totals(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        return queryAggregate(
                "SELECT " + SELECT_COLUMNS + " FROM rag_llm_usage_event "
                        + where(principalId),
                fromInclusive,
                toExclusive,
                principalId);
    }

    public List<DimensionAggregate> byModel(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        return queryDimension(
                "model_ref",
                "model_ref ASC",
                fromInclusive,
                toExclusive,
                principalId);
    }

    public List<DimensionAggregate> byPurpose(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        return queryDimension(
                "purpose",
                "CASE purpose "
                        + "WHEN 'CHAT' THEN 1 "
                        + "WHEN 'QUERY_TRANSFORM' THEN 2 "
                        + "WHEN 'QUERY_EXPAND' THEN 3 "
                        + "WHEN 'SUMMARY' THEN 4 ELSE 99 END",
                fromInclusive,
                toExclusive,
                principalId);
    }

    public List<DimensionAggregate> byMode(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        return queryDimension(
                "chat_mode",
                "CASE chat_mode "
                        + "WHEN 'PLAIN' THEN 1 "
                        + "WHEN 'KNOWLEDGE' THEN 2 "
                        + "WHEN 'AGENT' THEN 3 ELSE 99 END",
                fromInclusive,
                toExclusive,
                principalId);
    }

    public List<DimensionAggregate> byDay(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        String sql = "SELECT (started_at AT TIME ZONE 'UTC')::date AS dimension_key, "
                + SELECT_COLUMNS
                + " FROM rag_llm_usage_event "
                + where(principalId)
                + " GROUP BY (started_at AT TIME ZONE 'UTC')::date "
                + "ORDER BY (started_at AT TIME ZONE 'UTC')::date ASC";
        return jdbcTemplate.query(
                sql,
                this::mapDimension,
                args(fromInclusive, toExclusive, principalId));
    }

    public List<CostAggregate> costs(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        String sql = """
                SELECT cost_unit AS unit,
                       COALESCE(SUM(configured_cost), 0) AS configured_cost,
                       COUNT(*) AS invocation_count,
                       COUNT(*) FILTER (WHERE cost_available) AS cost_available_count
                FROM rag_llm_usage_event
                %s
                GROUP BY cost_unit
                ORDER BY cost_unit ASC
                """.formatted(where(principalId));
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new CostAggregate(
                        requiredText(rs.getString("unit"), "cost_unit"),
                        nonNegativeDecimal(rs.getBigDecimal("configured_cost")),
                        nonNegativeLong(rs.getLong("invocation_count"), "invocation_count"),
                        nonNegativeLong(rs.getLong("cost_available_count"),
                                "cost_available_count")),
                args(fromInclusive, toExclusive, principalId));
    }

    private List<DimensionAggregate> queryDimension(
            String dimensionColumn,
            String orderBy,
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        String sql = "SELECT " + dimensionColumn + " AS dimension_key, "
                + SELECT_COLUMNS
                + " FROM rag_llm_usage_event "
                + where(principalId)
                + " GROUP BY " + dimensionColumn
                + " ORDER BY " + orderBy;
        return jdbcTemplate.query(
                sql,
                this::mapDimension,
                args(fromInclusive, toExclusive, principalId));
    }

    private UsageAggregate queryAggregate(
            String sql,
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        return jdbcTemplate.queryForObject(
                sql,
                this::mapAggregate,
                args(fromInclusive, toExclusive, principalId));
    }

    private DimensionAggregate mapDimension(ResultSet rs, int rowNum)
            throws SQLException {
        Object raw = rs.getObject("dimension_key");
        String dimension = raw instanceof LocalDate date
                ? date.toString()
                : requiredText(String.valueOf(raw), "dimension_key");
        return new DimensionAggregate(dimension, mapAggregate(rs, rowNum));
    }

    private UsageAggregate mapAggregate(ResultSet rs, int rowNum)
            throws SQLException {
        return new UsageAggregate(
                nonNegativeLong(rs.getLong("logical_execution_count"),
                        "logical_execution_count"),
                nonNegativeLong(rs.getLong("invocation_count"), "invocation_count"),
                nonNegativeLong(rs.getLong("succeeded_count"), "succeeded_count"),
                nonNegativeLong(rs.getLong("failed_count"), "failed_count"),
                nonNegativeLong(rs.getLong("cancelled_count"), "cancelled_count"),
                nonNegativeDecimal(rs.getBigDecimal("prompt_tokens")),
                nonNegativeDecimal(rs.getBigDecimal("completion_tokens")),
                nonNegativeDecimal(rs.getBigDecimal("total_tokens")),
                nonNegativeLong(rs.getLong("usage_available_count"),
                        "usage_available_count"),
                nonNegativeLong(rs.getLong("usage_unavailable_count"),
                        "usage_unavailable_count"),
                nonNegativeLong(rs.getLong("pricing_unavailable_count"),
                        "pricing_unavailable_count"),
                nonNegativeLong(rs.getLong("cost_unavailable_count"),
                        "cost_unavailable_count"));
    }

    private static String where(String principalId) {
        return principalId == null
                ? WHERE_PREFIX
                : WHERE_PREFIX + " AND owner_principal_id = ?";
    }

    private static Object[] args(
            Instant fromInclusive,
            Instant toExclusive,
            String principalId) {
        if (principalId == null) {
            return new Object[]{
                    Timestamp.from(fromInclusive),
                    Timestamp.from(toExclusive)};
        }
        return new Object[]{
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive),
                principalId};
    }

    private static long nonNegativeLong(long value, String field) {
        if (value < 0) {
            throw new IllegalStateException(field + " must not be negative");
        }
        return value;
    }

    private static BigDecimal nonNegativeDecimal(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalStateException("usage aggregate must be non-negative");
        }
        return value;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value;
    }

    public record UsageAggregate(
            long logicalExecutionCount,
            long invocationCount,
            long succeededCount,
            long failedCount,
            long cancelledCount,
            BigDecimal promptTokens,
            BigDecimal completionTokens,
            BigDecimal totalTokens,
            long usageAvailableCount,
            long usageUnavailableCount,
            long pricingUnavailableCount,
            long costUnavailableCount) {
    }

    public record DimensionAggregate(
            String dimension,
            UsageAggregate aggregate) {
    }

    public record CostAggregate(
            String unit,
            BigDecimal configuredCost,
            long invocationCount,
            long costAvailableCount) {
    }
}
