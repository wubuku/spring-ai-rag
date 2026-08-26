package com.springairag.core.service;

import com.springairag.api.dto.DocumentSyncRunItemCurrentSummary;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Sync Run item receipt 的只读 PostgreSQL 查询。
 */
@Repository
public class DocumentSyncRunItemReceiptRepository {

    private static final String PAGE_COLUMNS = """
            SELECT external_id, document_kind, source_revision, document_id,
                   status, error_code, error_message, seen_at
            FROM rag_document_sync_run_items
            """;

    private final JdbcTemplate jdbcTemplate;

    public DocumentSyncRunItemReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DocumentSyncRunItemCurrentSummary currentSummary(UUID runId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'APPLIED') AS applied,
                       COUNT(*) FILTER (WHERE status = 'UNCHANGED') AS unchanged,
                       COUNT(*) FILTER (
                           WHERE status = 'SKIPPED_NEWER_MUTATION'
                       ) AS skipped_newer_mutation,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM rag_document_sync_run_items
                WHERE run_id = ?
                """,
                (rs, rowNum) -> new DocumentSyncRunItemCurrentSummary(
                        rs.getLong("total"),
                        rs.getLong("applied"),
                        rs.getLong("unchanged"),
                        rs.getLong("skipped_newer_mutation"),
                        rs.getLong("failed")),
                runId);
    }

    public List<ReceiptRow> page(
            UUID runId,
            DocumentSyncItemStatus statusFilter,
            DocumentSyncRunItemCursorCodec.CursorPosition cursor,
            int limitPlusOne) {
        if (statusFilter == null && cursor == null) {
            return jdbcTemplate.query(
                    PAGE_COLUMNS + """
                            WHERE run_id = ?
                            ORDER BY seen_at, external_id
                            LIMIT ?
                            """,
                    this::mapRow,
                    runId,
                    limitPlusOne);
        }
        if (statusFilter == null) {
            return jdbcTemplate.query(
                    PAGE_COLUMNS + """
                            WHERE run_id = ?
                              AND (seen_at, external_id) > (?, ?)
                            ORDER BY seen_at, external_id
                            LIMIT ?
                            """,
                    this::mapRow,
                    runId,
                    cursor.seenAt(),
                    cursor.externalId(),
                    limitPlusOne);
        }
        if (cursor == null) {
            return jdbcTemplate.query(
                    PAGE_COLUMNS + """
                            WHERE run_id = ? AND status = ?
                            ORDER BY seen_at, external_id
                            LIMIT ?
                            """,
                    this::mapRow,
                    runId,
                    statusFilter.name(),
                    limitPlusOne);
        }
        return jdbcTemplate.query(
                PAGE_COLUMNS + """
                        WHERE run_id = ? AND status = ?
                          AND (seen_at, external_id) > (?, ?)
                        ORDER BY seen_at, external_id
                        LIMIT ?
                        """,
                this::mapRow,
                runId,
                statusFilter.name(),
                cursor.seenAt(),
                cursor.externalId(),
                limitPlusOne);
    }

    private ReceiptRow mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ReceiptRow(
                rs.getString("external_id"),
                DocumentSyncDocumentKind.valueOf(rs.getString("document_kind")),
                rs.getString("source_revision"),
                (Long) rs.getObject("document_id"),
                DocumentSyncItemStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                readOffsetDateTime(rs.getObject("seen_at")));
    }

    private static OffsetDateTime readOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toOffsetDateTime();
        }
        throw new IllegalStateException(
                "Unsupported sync-run item timestamp type: "
                        + (value == null ? "null" : value.getClass()));
    }

    public record ReceiptRow(
            String externalId,
            DocumentSyncDocumentKind documentKind,
            String sourceRevision,
            Long documentId,
            DocumentSyncItemStatus status,
            String errorCode,
            String errorMessage,
            OffsetDateTime seenAt) {
    }
}
