package com.springairag.core.repository;

import com.springairag.core.chat.ChatTurnOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JDBC repository for durable Chat turn operations.
 */
@Repository
public class ChatTurnOperationRepository {

    private static final String COLUMNS = """
            id, owner_principal_id, idempotency_key_sha256,
            request_fingerprint_sha256, fingerprint_version, session_id,
            turn_id, transport, status, operation_token, lease_expires_at,
            attempt_count, row_version, response_version, execution_snapshot,
            response_payload, error_code, error_payload,
            authorization_scope_snapshot, created_at, updated_at, completed_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public ChatTurnOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ChatTurnOperation find(String ownerPrincipalId, String keyHash) {
        List<ChatTurnOperation> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + """
                        FROM rag_chat_turn_operations
                        WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                        """,
                this::map,
                ownerPrincipalId,
                keyHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public ChatTurnOperation findByTurn(String ownerPrincipalId, UUID turnId) {
        List<ChatTurnOperation> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + """
                        FROM rag_chat_turn_operations
                        WHERE owner_principal_id = ? AND turn_id = ?
                        """,
                this::map,
                ownerPrincipalId,
                turnId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean insert(
            String ownerPrincipalId,
            String keyHash,
            String fingerprintHash,
            String sessionId,
            UUID turnId,
            ChatTurnOperation.Transport transport,
            UUID operationToken,
            int leaseMs,
            String authorizationSnapshot) {
        return insert(
                ownerPrincipalId,
                keyHash,
                fingerprintHash,
                sessionId,
                turnId,
                transport,
                operationToken,
                leaseMs,
                null,
                authorizationSnapshot);
    }

    public boolean insert(
            String ownerPrincipalId,
            String keyHash,
            String fingerprintHash,
            String sessionId,
            UUID turnId,
            ChatTurnOperation.Transport transport,
            UUID operationToken,
            int leaseMs,
            String executionSnapshot,
            String authorizationSnapshot) {
        int affected = jdbcTemplate.update(
                """
                INSERT INTO rag_chat_turn_operations (
                    owner_principal_id, idempotency_key_sha256,
                    request_fingerprint_sha256, fingerprint_version,
                    session_id, turn_id, transport, status, operation_token,
                    lease_expires_at, attempt_count, row_version,
                    response_version, execution_snapshot,
                    authorization_scope_snapshot)
                VALUES (?, ?, ?, 1, ?, ?, ?, 'IN_PROGRESS', ?,
                        clock_timestamp() + (? * interval '1 millisecond'),
                        1, 0, 1, ?::jsonb, ?::jsonb)
                ON CONFLICT (owner_principal_id, idempotency_key_sha256)
                DO NOTHING
                """,
                ownerPrincipalId,
                keyHash,
                fingerprintHash,
                sessionId,
                turnId,
                transport.name(),
                operationToken,
                leaseMs,
                executionSnapshot,
                authorizationSnapshot);
        return affected == 1;
    }

    public ChatTurnOperation reclaim(
            ChatTurnOperation operation,
            UUID newToken,
            int leaseMs,
            int maxAttempts) {
        return reclaim(operation, newToken, leaseMs, null, maxAttempts);
    }

    public ChatTurnOperation reclaim(
            ChatTurnOperation operation,
            UUID newToken,
            int leaseMs,
            String executionSnapshot,
            int maxAttempts) {
        int affected = jdbcTemplate.update(
                """
                UPDATE rag_chat_turn_operations
                SET operation_token = ?,
                    lease_expires_at = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    attempt_count = attempt_count + 1,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp(),
                    execution_snapshot = COALESCE(
                        execution_snapshot, ?::jsonb)
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND operation_token = ?
                  AND row_version = ?
                  AND lease_expires_at <= clock_timestamp()
                  AND attempt_count < ?
                """,
                newToken,
                leaseMs,
                executionSnapshot,
                operation.id(),
                operation.operationToken(),
                operation.rowVersion(),
                maxAttempts);
        return affected == 1
                ? find(operation.ownerPrincipalId(), operation.idempotencyKeySha256())
                : null;
    }

    /**
     * Renews the durable operation lease using the same row-version fencing as
     * terminal writes. An expired or superseded worker can never revive itself.
     */
    public ChatTurnOperation renew(
            ChatTurnOperation operation,
            int leaseMs) {
        int affected = jdbcTemplate.update(
                """
                UPDATE rag_chat_turn_operations
                SET lease_expires_at = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND operation_token = ?
                  AND row_version = ?
                  AND lease_expires_at > clock_timestamp()
                """,
                leaseMs,
                operation.id(),
                operation.operationToken(),
                operation.rowVersion());
        return affected == 1
                ? find(operation.ownerPrincipalId(), operation.idempotencyKeySha256())
                : null;
    }

    /**
     * Turns an operation whose reclaim budget is exhausted into a stable
     * terminal failure. This is a CAS and therefore safe against a racing
     * reclaim or cleanup worker.
     */
    public boolean exhaustAttempts(
            ChatTurnOperation operation,
            String errorCode,
            String errorPayload) {
        return jdbcTemplate.update(
                """
                UPDATE rag_chat_turn_operations
                SET status = 'FAILED',
                    operation_token = NULL,
                    lease_expires_at = NULL,
                    error_code = ?,
                    error_payload = ?::jsonb,
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp(),
                    row_version = row_version + 1
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND operation_token = ?
                  AND row_version = ?
                  AND lease_expires_at <= clock_timestamp()
                """,
                errorCode,
                errorPayload,
                operation.id(),
                operation.operationToken(),
                operation.rowVersion()) == 1;
    }

    public boolean completeSuccess(
            ChatTurnOperation operation,
            String executionSnapshot,
            String responsePayload) {
        return completeSuccess(
                operation,
                executionSnapshot,
                responsePayload,
                operation.authorizationScopeSnapshot());
    }

    public boolean completeSuccess(
            ChatTurnOperation operation,
            String executionSnapshot,
            String responsePayload,
            String authorizationSnapshot) {
        return jdbcTemplate.update(
                """
                UPDATE rag_chat_turn_operations
                SET status = 'SUCCEEDED',
                    operation_token = NULL,
                    lease_expires_at = NULL,
                    execution_snapshot = ?::jsonb,
                    response_payload = ?::jsonb,
                    authorization_scope_snapshot = ?::jsonb,
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp(),
                    row_version = row_version + 1
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND operation_token = ?
                  AND row_version = ?
                  AND lease_expires_at > clock_timestamp()
                """,
                executionSnapshot,
                responsePayload,
                authorizationSnapshot,
                operation.id(),
                operation.operationToken(),
                operation.rowVersion()) == 1;
    }

    public boolean completeFailure(
            ChatTurnOperation operation,
            String errorCode,
            String errorPayload) {
        return jdbcTemplate.update(
                """
                UPDATE rag_chat_turn_operations
                SET status = 'FAILED',
                    operation_token = NULL,
                    lease_expires_at = NULL,
                    error_code = ?,
                    error_payload = ?::jsonb,
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp(),
                    row_version = row_version + 1
                WHERE id = ?
                  AND status = 'IN_PROGRESS'
                  AND operation_token = ?
                  AND row_version = ?
                  AND lease_expires_at > clock_timestamp()
                """,
                errorCode,
                errorPayload,
                operation.id(),
                operation.operationToken(),
                operation.rowVersion()) == 1;
    }

    public int deleteExpired(int batchSize, int retentionHours) {
        return jdbcTemplate.update(
                """
                WITH candidates AS (
                    SELECT id, owner_principal_id, session_id, status,
                           operation_token, lease_expires_at, row_version,
                           updated_at, completed_at
                    FROM rag_chat_turn_operations
                    WHERE (
                        (
                            status IN ('SUCCEEDED', 'FAILED')
                            AND completed_at < clock_timestamp()
                                - (? * interval '1 hour')
                        ) OR (
                            status = 'IN_PROGRESS'
                            AND lease_expires_at < clock_timestamp()
                            AND updated_at < clock_timestamp()
                                - (? * interval '1 hour')
                        )
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM rag_chat_session_lease lease
                        WHERE lease.owner_principal_id =
                            rag_chat_turn_operations.owner_principal_id
                          AND lease.session_id =
                            rag_chat_turn_operations.session_id
                          AND lease.expires_at > clock_timestamp()
                    )
                    ORDER BY id
                    LIMIT ?
                )
                DELETE FROM rag_chat_turn_operations operation
                USING candidates
                WHERE operation.id = candidates.id
                  AND operation.owner_principal_id =
                        candidates.owner_principal_id
                  AND operation.session_id = candidates.session_id
                  AND operation.status = candidates.status
                  AND operation.operation_token IS NOT DISTINCT FROM
                        candidates.operation_token
                  AND operation.lease_expires_at IS NOT DISTINCT FROM
                        candidates.lease_expires_at
                  AND operation.row_version = candidates.row_version
                  AND operation.updated_at IS NOT DISTINCT FROM
                        candidates.updated_at
                  AND operation.completed_at IS NOT DISTINCT FROM
                        candidates.completed_at
                  AND NOT EXISTS (
                        SELECT 1
                        FROM rag_chat_session_lease lease
                        WHERE lease.owner_principal_id =
                            operation.owner_principal_id
                          AND lease.session_id = operation.session_id
                          AND lease.expires_at > clock_timestamp()
                  )
                """,
                retentionHours,
                retentionHours,
                batchSize);
    }

    private ChatTurnOperation map(ResultSet rs, int rowNumber) throws SQLException {
        return new ChatTurnOperation(
                rs.getLong("id"),
                rs.getString("owner_principal_id"),
                rs.getString("idempotency_key_sha256"),
                rs.getString("request_fingerprint_sha256"),
                rs.getInt("fingerprint_version"),
                rs.getString("session_id"),
                rs.getObject("turn_id", UUID.class),
                ChatTurnOperation.Transport.valueOf(rs.getString("transport")),
                ChatTurnOperation.Status.valueOf(rs.getString("status")),
                rs.getObject("operation_token", UUID.class),
                instant(rs.getTimestamp("lease_expires_at")),
                rs.getInt("attempt_count"),
                rs.getLong("row_version"),
                rs.getInt("response_version"),
                rs.getString("execution_snapshot"),
                rs.getString("response_payload"),
                rs.getString("error_code"),
                rs.getString("error_payload"),
                rs.getString("authorization_scope_snapshot"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private Instant instant(Timestamp value) {
        return value != null ? value.toInstant() : null;
    }
}
