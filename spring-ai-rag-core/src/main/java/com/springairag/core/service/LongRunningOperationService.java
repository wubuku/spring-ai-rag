package com.springairag.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.LongRunningOperationResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.exception.RagException;
import com.springairag.core.exception.LongRunningOperationCapacityException;
import com.springairag.core.observability.IntegrationObservationContext;
import com.springairag.core.security.ApiKeyCollectionAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 长任务回执与状态变更的 owner-scoped HTTP 边界。 */
@Service
public class LongRunningOperationService {

    private static final int MAX_ACTIVE_OPERATIONS = 500;
    private static final int MAX_ACTIVE_OPERATIONS_PER_OWNER = 4;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CollectionPurgeAuthorization purgeAuthorization;
    private final TransactionTemplate transactionTemplate;

    public LongRunningOperationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CollectionPurgeAuthorization purgeAuthorization,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.purgeAuthorization = purgeAuthorization;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public LongRunningOperationResponse get(UUID operationId, HttpServletRequest request) {
        Row row = load(operationId, request);
        authorizeResource(row, request);
        return response(row);
    }

    public JsonNode getResult(UUID operationId, HttpServletRequest request) {
        Row row = load(operationId, request);
        authorizeResource(row, request);
        if (row.resultExpiresAt() != null
                && !row.resultExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new RagException(
                    ErrorCode.LONG_RUNNING_OPERATION_RESULT_EXPIRED,
                    "The operation result has expired");
        }
        if (!terminal(row.status()) || row.resultPayload() == null) {
            throw new RagException(
                    ErrorCode.LONG_RUNNING_OPERATION_RESULT_NOT_READY,
                    "The operation result is not ready");
        }
        try {
            return objectMapper.readTree(row.resultPayload());
        } catch (Exception exception) {
            throw new IllegalStateException("Stored operation result is invalid JSON", exception);
        }
    }

    public LongRunningOperationResponse cancel(
            UUID operationId,
            HttpServletRequest request) {
        Row row = load(operationId, request);
        authorizeResource(row, request);
        LongRunningOperationResponse result = transactionTemplate.execute(status -> {
            Row current = load(operationId, request);
            authorizeResource(current, request);
            if (terminal(current.status())) {
                throw stateConflict("A terminal operation cannot be cancelled");
            }
            if ("COLLECTION_PURGE".equals(current.operationKind())
                    && current.purgeFenceCompletedAt() != null) {
                throw stateConflict("A fenced Collection purge cannot be cancelled");
            }
            if ("COLLECTION_PURGE".equals(current.operationKind())
                    && current.status().equals("RUNNING")) {
                int requested = jdbcTemplate.update("""
                        UPDATE rag_long_running_operations
                        SET cancel_requested_at = COALESCE(cancel_requested_at, clock_timestamp()),
                            updated_at = clock_timestamp()
                        WHERE id = ? AND owner_principal_id = ?
                          AND status = 'RUNNING'
                          AND purge_fence_completed_at IS NULL
                          AND cancel_requested_at IS NULL
                        """, current.id(), current.ownerPrincipalId());
                if (requested == 0 && current.cancelRequestedAt() == null) {
                    throw stateConflict("Operation state changed while requesting cancellation");
                }
            } else if ("EMBEDDING_PROFILE_SWITCH".equals(current.operationKind())
                    && ("RUNNING".equals(current.status())
                    || hasActiveChildRuns(current.id()))) {
                int requested = jdbcTemplate.update("""
                        UPDATE rag_long_running_operations
                        SET cancel_requested_at = COALESCE(cancel_requested_at, clock_timestamp()),
                            updated_at = clock_timestamp()
                        WHERE id = ? AND owner_principal_id = ?
                          AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'DEGRADED')
                        """, current.id(), current.ownerPrincipalId());
                if (requested != 1) {
                    throw stateConflict("Operation state changed while requesting cancellation");
                }
            } else {
                int cancelled = jdbcTemplate.update("""
                        UPDATE rag_long_running_operations
                        SET status = 'CANCELLED', cancel_requested_at =
                                COALESCE(cancel_requested_at, clock_timestamp()),
                            terminal_reason = 'CLIENT_CANCELLED',
                            result_payload = '{"status":"CANCELLED"}'::jsonb,
                            lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                            next_retry_at = NULL, finished_at = clock_timestamp(),
                            updated_at = clock_timestamp(),
                            result_expires_at = clock_timestamp() + INTERVAL '7 days',
                            tombstone_expires_at = GREATEST(
                                created_at + INTERVAL '30 days',
                                clock_timestamp() + INTERVAL '7 days')
                        WHERE id = ? AND owner_principal_id = ?
                          AND status IN ('QUEUED', 'RETRY_WAIT', 'DEGRADED')
                          AND purge_fence_completed_at IS NULL
                        """, current.id(), current.ownerPrincipalId());
                if (cancelled != 1) {
                    throw stateConflict("Operation state changed while cancelling");
                }
                releaseReservations(current);
            }
            return response(load(operationId, request));
        });
        if (result == null) {
            throw stateConflict("Operation cancellation did not complete");
        }
        return result;
    }

    public LongRunningOperationResponse retry(
            UUID operationId,
            HttpServletRequest request) {
        Row row = load(operationId, request);
        authorizeResource(row, request);
        LongRunningOperationResponse result = transactionTemplate.execute(status -> {
            Row current = load(operationId, request);
            authorizeResource(current, request);
            if (!("DEGRADED".equals(current.status())
                    || "RETRY_WAIT".equals(current.status()))) {
                throw stateConflict("Only degraded or due retry-wait operations can be retried");
            }
            if ("RETRY_WAIT".equals(current.status())
                    && current.nextRetryAt() != null
                    && current.nextRetryAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw stateConflict("Operation retry backoff has not elapsed");
            }
            int queued = jdbcTemplate.update("""
                    UPDATE rag_long_running_operations
                    SET status = 'QUEUED', attempt_count = attempt_count + 1,
                        cancel_requested_at = NULL, next_retry_at = NULL,
                        error_code = NULL, error_payload = NULL,
                        progress_payload = CASE WHEN status = 'DEGRADED'
                            THEN progress_payload || '{"retryRequested":true}'::jsonb
                            ELSE progress_payload END,
                        terminal_reason = NULL,
                        lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                        heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = ? AND owner_principal_id = ?
                      AND status = ?
                    """, current.id(), current.ownerPrincipalId(), current.status());
            if (queued != 1) {
                throw stateConflict("Operation state changed while retrying");
            }
            if ("DEGRADED".equals(current.status())) {
                jdbcTemplate.update("""
                        INSERT INTO rag_long_running_operation_owner_capacity (
                            owner_principal_id, active_count)
                        VALUES (?, 0) ON CONFLICT (owner_principal_id) DO NOTHING
                        """, current.ownerPrincipalId());
                int ownerReserved = jdbcTemplate.update("""
                        UPDATE rag_long_running_operation_owner_capacity
                        SET active_count = active_count + 1,
                            updated_at = clock_timestamp()
                        WHERE owner_principal_id = ? AND active_count < ?
                        """, current.ownerPrincipalId(), MAX_ACTIVE_OPERATIONS_PER_OWNER);
                if (ownerReserved != 1) {
                    throw new LongRunningOperationCapacityException(
                            "Owner operation capacity is exhausted");
                }
            }
            return response(load(operationId, request));
        });
        if (result == null) {
            throw stateConflict("Operation retry did not complete");
        }
        return result;
    }

    private Row load(UUID operationId, HttpServletRequest request) {
        String owner = ChatPrincipal.from(request).id();
        var rows = jdbcTemplate.query("""
                SELECT operation.id, operation.owner_principal_id,
                       operation.resource_type, operation.collection_id,
                       collection.collection_key, operation.operation_kind,
                       operation.status, operation.progress_payload::text,
                       operation.result_payload::text, operation.error_code,
                       operation.target_embedding_profile_id, profile.profile_key,
                       operation.next_retry_at, operation.cancel_requested_at,
                       operation.purge_fence_completed_at, operation.created_at,
                       operation.updated_at, operation.result_expires_at,
                       operation.finished_at,
                       CASE WHEN operation.next_retry_at IS NULL THEN NULL
                            ELSE GREATEST(0, CEIL(EXTRACT(EPOCH FROM
                                (operation.next_retry_at - clock_timestamp())))::integer)
                       END AS retry_after_seconds
                FROM rag_long_running_operations operation
                JOIN rag_collection collection ON collection.id = operation.collection_id
                LEFT JOIN rag_embedding_profiles profile
                  ON profile.id = operation.target_embedding_profile_id
                WHERE operation.id = ? AND operation.owner_principal_id = ?
                """,
                (rs, rowNum) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("owner_principal_id"),
                        rs.getString("resource_type"),
                        rs.getLong("collection_id"),
                        rs.getString("collection_key"),
                        rs.getString("operation_kind"),
                        rs.getString("status"),
                        rs.getString("progress_payload"),
                        rs.getString("result_payload"),
                        rs.getString("error_code"),
                        rs.getObject("target_embedding_profile_id", Long.class),
                        rs.getString("profile_key"),
                        timestamp(rs.getTimestamp("next_retry_at")),
                        timestamp(rs.getTimestamp("cancel_requested_at")),
                        timestamp(rs.getTimestamp("purge_fence_completed_at")),
                        timestamp(rs.getTimestamp("created_at")),
                        timestamp(rs.getTimestamp("updated_at")),
                        timestamp(rs.getTimestamp("result_expires_at")),
                        timestamp(rs.getTimestamp("finished_at")),
                        (Integer) rs.getObject("retry_after_seconds")),
                operationId, owner);
        if (rows.isEmpty()) {
            throw new RagException(
                    ErrorCode.LONG_RUNNING_OPERATION_NOT_FOUND,
                    "Long-running operation not found");
        }
        Row row = rows.getFirst();
        IntegrationObservationContext.addAuthorizedCollection(request, row.collectionId());
        return row;
    }

    private void authorizeResource(Row row, HttpServletRequest request) {
        if ("COLLECTION_PURGE".equals(row.operationKind())) {
            purgeAuthorization.requireAllowed(request);
            return;
        }
        try {
            ApiKeyCollectionAccess.requireCollectionId(
                    row.collectionId(), ApiKeyCollectionAccess.currentPolicy(request));
        } catch (SecurityException exception) {
            throw new RagException(ErrorCode.FORBIDDEN, "Collection is not authorized");
        }
    }

    private boolean hasActiveChildRuns(UUID operationId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rag_long_running_operation_run_refs reference
                JOIN rag_document_processing_runs run ON run.id = reference.run_id
                WHERE reference.operation_id = ? AND run.status IN ('QUEUED', 'RUNNING')
                """, Long.class, operationId);
        return count != null && count > 0;
    }

    private void releaseReservations(Row row) {
        jdbcTemplate.update("""
                UPDATE rag_long_running_operation_owner_capacity
                SET active_count = GREATEST(0, active_count - 1),
                    updated_at = clock_timestamp()
                WHERE owner_principal_id = ?
                """, row.ownerPrincipalId());
        if (!"DEGRADED".equals(row.status())) {
            jdbcTemplate.update("""
                    UPDATE rag_long_running_operation_global_capacity
                    SET active_count = GREATEST(0, active_count - 1),
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                    """);
        }
        jdbcTemplate.update("""
                DELETE FROM rag_collection_operation_slot
                WHERE collection_id = ? AND operation_id = ?
                """, row.collectionId(), row.id());
    }

    private LongRunningOperationResponse response(Row row) {
        Map<String, Object> failure = row.errorCode() == null
                ? null : Map.of("code", row.errorCode());
        Boolean cancelRequested = row.cancelRequestedAt() == null ? null : Boolean.TRUE;
        return new LongRunningOperationResponse(
                row.id(), row.operationKind(), row.resourceType(), row.collectionId(),
                row.collectionKey(), row.status(), jsonMap(row.progressPayload()),
                row.createdAt(), row.updatedAt(),
                "/api/v1/rag/operations/" + row.id(),
                "/api/v1/rag/operations/" + row.id() + "/result",
                row.targetProfileKey(), row.nextRetryAt(), row.retryAfterSeconds(),
                cancelRequested, failure);
    }

    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static OffsetDateTime timestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status)
                || "CANCELLED".equals(status)
                || "STALE".equals(status);
    }

    private static RagException stateConflict(String message) {
        return new RagException(ErrorCode.LONG_RUNNING_OPERATION_STATE_CONFLICT, message);
    }

    private record Row(
            UUID id,
            String ownerPrincipalId,
            String resourceType,
            long collectionId,
            String collectionKey,
            String operationKind,
            String status,
            String progressPayload,
            String resultPayload,
            String errorCode,
            Long targetProfileId,
            String targetProfileKey,
            OffsetDateTime nextRetryAt,
            OffsetDateTime cancelRequestedAt,
            OffsetDateTime purgeFenceCompletedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime resultExpiresAt,
            OffsetDateTime finishedAt,
            Integer retryAfterSeconds) {
    }
}
