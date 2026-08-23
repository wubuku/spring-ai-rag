package com.springairag.core.ratelimit;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/** PostgreSQL 共享固定 UTC 分钟配额存储。 */
public class PostgresRateLimitStore {

    private static final String CONSUME_SQL = """
            INSERT INTO rag_api_rate_limit_bucket
                (principal_id, window_start, request_count, updated_at)
            VALUES
                (?, date_trunc('minute', clock_timestamp()), 1, clock_timestamp())
            ON CONFLICT (principal_id, window_start) DO UPDATE
            SET request_count = rag_api_rate_limit_bucket.request_count + 1,
                updated_at = clock_timestamp()
            WHERE rag_api_rate_limit_bucket.request_count < ?
            RETURNING request_count, window_start,
                GREATEST(1, LEAST(60, CEIL(EXTRACT(EPOCH FROM
                    (window_start + INTERVAL '1 minute' - clock_timestamp())))::INTEGER))
                    AS retry_after
            """;

    private static final String CURRENT_SQL = """
            SELECT request_count, window_start,
                GREATEST(1, LEAST(60, CEIL(EXTRACT(EPOCH FROM
                    (window_start + INTERVAL '1 minute' - clock_timestamp())))::INTEGER))
                    AS retry_after
            FROM rag_api_rate_limit_bucket
            WHERE principal_id = ?
              AND window_start = date_trunc('minute', clock_timestamp())
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresRateLimitStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Decision consume(String principalId, int limit) {
        List<Decision> accepted = jdbcTemplate.query(
                CONSUME_SQL,
                (rs, rowNum) -> new Decision(
                        true,
                        rs.getInt("request_count"),
                        rs.getObject("window_start", OffsetDateTime.class),
                        rs.getInt("retry_after")),
                principalId,
                limit);
        if (!accepted.isEmpty()) {
            return accepted.getFirst();
        }
        return jdbcTemplate.query(
                        CURRENT_SQL,
                        (rs, rowNum) -> new Decision(
                                false,
                                rs.getInt("request_count"),
                                rs.getObject("window_start", OffsetDateTime.class),
                                rs.getInt("retry_after")),
                        principalId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Rate limit bucket disappeared after a rejected consume"));
    }

    public int cleanup(int retentionMinutes, int batchSize) {
        return jdbcTemplate.update("""
                WITH doomed AS (
                    SELECT ctid
                    FROM rag_api_rate_limit_bucket
                    WHERE updated_at < clock_timestamp() - (? * INTERVAL '1 minute')
                    ORDER BY updated_at
                    LIMIT ?
                )
                DELETE FROM rag_api_rate_limit_bucket bucket
                USING doomed
                WHERE bucket.ctid = doomed.ctid
                """, retentionMinutes, batchSize);
    }

    public record Decision(
            boolean allowed,
            int requestCount,
            OffsetDateTime windowStart,
            int retryAfterSeconds) {
    }
}
