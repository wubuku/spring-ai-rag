package com.springairag.core.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC persistence boundary for the durable model invocation ledger.
 *
 * <p>The repository stores only the bounded fields present in
 * {@link LlmUsageEvent}. It deliberately has no update or detail-query API.</p>
 */
@Repository
public class LlmUsageRepository {

    private static final String INSERT_SQL = """
            INSERT INTO rag_llm_usage_event (
                invocation_id, logical_execution_id, call_ordinal,
                owner_principal_id, session_id, request_trace_id, model_ref,
                chat_mode, purpose, streaming, outcome,
                prompt_tokens, completion_tokens, total_tokens, usage_available,
                input_cost_per_million, output_cost_per_million, pricing_available,
                configured_cost, cost_available, cost_unit, duration_ms,
                started_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?)
            ON CONFLICT (logical_execution_id, call_ordinal) DO NOTHING
            """;

    private static final String DELETE_EXPIRED_SQL = """
            WITH victims AS (
                SELECT id
                FROM rag_llm_usage_event
                WHERE created_at < ?
                ORDER BY created_at ASC, id ASC
                LIMIT ?
            )
            DELETE FROM rag_llm_usage_event event
            USING victims
            WHERE event.id = victims.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public LlmUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts one terminal event and treats a duplicate execution ordinal as
     * an already-recorded terminal state.
     *
     * @return {@code true} when a row was inserted, {@code false} for a duplicate
     */
    public boolean insert(LlmUsageEvent event, int timeoutMs) {
        if (event == null) {
            return false;
        }
        int affected = jdbcTemplate.update(
                INSERT_SQL,
                statement -> {
                    statement.setQueryTimeout(timeoutSeconds(timeoutMs));
                    set(statement, 1, event.invocationId());
                    set(statement, 2, event.logicalExecutionId());
                    set(statement, 3, event.callOrdinal());
                    set(statement, 4, event.principalId());
                    set(statement, 5, event.sessionId());
                    set(statement, 6, event.requestTraceId());
                    set(statement, 7, event.modelRef());
                    set(statement, 8, event.chatMode().name());
                    set(statement, 9, event.purpose().name());
                    set(statement, 10, event.streaming());
                    set(statement, 11, event.outcome().name());
                    set(statement, 12, event.usage().promptTokens());
                    set(statement, 13, event.usage().completionTokens());
                    set(statement, 14, event.usage().totalTokens());
                    set(statement, 15, event.usage().available());
                    set(statement, 16, event.inputCostPerMillion());
                    set(statement, 17, event.outputCostPerMillion());
                    set(statement, 18, event.pricingAvailable());
                    set(statement, 19, event.configuredCost());
                    set(statement, 20, event.costAvailable());
                    set(statement, 21, event.costUnit());
                    set(statement, 22, event.durationMs());
                    set(statement, 23, Timestamp.from(event.startedAt()));
                    set(statement, 24, Timestamp.from(event.completedAt()));
                });
        return affected == 1;
    }

    /**
     * Deletes at most {@code batchSize} expired rows. The caller controls the
     * number of batches so maintenance remains bounded.
     */
    public int deleteExpired(Instant cutoff, int batchSize, int timeoutMs) {
        if (cutoff == null || batchSize < 1) {
            return 0;
        }
        return jdbcTemplate.update(
                DELETE_EXPIRED_SQL,
                statement -> {
                    statement.setQueryTimeout(timeoutSeconds(timeoutMs));
                    set(statement, 1, Timestamp.from(cutoff));
                    set(statement, 2, batchSize);
                });
    }

    private static int timeoutSeconds(int timeoutMs) {
        return Math.max(
                1,
                (int) Math.ceil(Math.max(1, timeoutMs) / 1_000.0));
    }

    private static void set(
            java.sql.PreparedStatement statement,
            int index,
            Object value) throws java.sql.SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setObject(index, value);
        }
    }
}
