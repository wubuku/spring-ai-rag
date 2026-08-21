package com.springairag.core.repository;

import com.springairag.core.chat.ChatPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for the owner-scoped conversation summary CAS record.
 */
@Repository
public class RagChatMemorySummaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagChatMemorySummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SummaryRow> find(
            ChatPrincipal principal,
            String sessionId) {
        List<SummaryRow> rows = jdbcTemplate.query("""
                SELECT summary_text, summary_model_ref, estimated_tokens,
                       version, summarized_through_history_id, updated_at
                FROM rag_chat_memory_summary
                WHERE owner_principal_id = ? AND session_id = ?
                """,
                (rs, rowNum) -> new SummaryRow(
                        rs.getLong("version"),
                        rs.getLong("summarized_through_history_id"),
                        rs.getString("summary_text"),
                        rs.getString("summary_model_ref"),
                        rs.getInt("estimated_tokens"),
                        rs.getTimestamp("updated_at").toInstant()),
                principal.id(), sessionId);
        return rows.stream().findFirst();
    }

    /**
     * Inserts version 1 or advances the current version exactly once.
     */
    public boolean saveCas(
            ChatPrincipal principal,
            String sessionId,
            long expectedVersion,
            long summarizedThroughHistoryId,
            String summaryText,
            int estimatedTokens,
            String modelRef) {
        if (summarizedThroughHistoryId <= 0) {
            throw new IllegalArgumentException(
                    "summarizedThroughHistoryId must be greater than zero");
        }
        if (summaryText == null || summaryText.isBlank()) {
            throw new IllegalArgumentException("summaryText must not be blank");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException(
                    "estimatedTokens must not be negative");
        }
        if (expectedVersion == 0) {
            int inserted = jdbcTemplate.update("""
                    INSERT INTO rag_chat_memory_summary (
                        owner_principal_id, session_id, summary_text,
                        summarized_through_history_id, estimated_tokens,
                        summary_model_ref, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                    ON CONFLICT (owner_principal_id, session_id) DO NOTHING
                    """,
                    principal.id(), sessionId, summaryText,
                    summarizedThroughHistoryId, estimatedTokens, modelRef);
            return inserted == 1;
        }
        return jdbcTemplate.update("""
                UPDATE rag_chat_memory_summary
                SET version = version + 1,
                    summarized_through_history_id = ?,
                    estimated_tokens = ?,
                    summary_text = ?,
                    summary_model_ref = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_principal_id = ?
                  AND session_id = ?
                  AND version = ?
                  AND summarized_through_history_id < ?
                """,
                summarizedThroughHistoryId, estimatedTokens, summaryText,
                modelRef, principal.id(), sessionId, expectedVersion,
                summarizedThroughHistoryId) == 1;
    }

    public int delete(ChatPrincipal principal, String sessionId) {
        return jdbcTemplate.update("""
                DELETE FROM rag_chat_memory_summary
                WHERE owner_principal_id = ? AND session_id = ?
                """, principal.id(), sessionId);
    }

    public record SummaryRow(
            long version,
            long summarizedThroughHistoryId,
            String text,
            String modelRef,
            int estimatedTokens,
            Instant updatedAt) {
    }
}
