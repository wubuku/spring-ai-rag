package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileRegistry;
import com.springairag.core.config.EmbeddingVectorColumns;
import com.springairag.core.documentprocessing.DocumentProcessingRun;
import com.springairag.core.documentprocessing.DocumentProcessingRunService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 以小页调度 Profile 切换；逐文档状态继续由 Processing Run 维护。 */
@Component
@Profile("postgresql")
public class EmbeddingProfileOperationWorker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileOperationWorker.class);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_COORDINATOR_ATTEMPTS = 5;
    private static final long LEASE_MILLIS = 120_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RagDocumentRepository documentRepository;
    private final EmbeddingDispatchService dispatchService;
    private final EmbeddingProfileRegistry profileRegistry;
    private final DocumentProcessingRunService runService;
    private final TransactionTemplate transactionTemplate;
    private final String workerId = UUID.randomUUID().toString();

    public EmbeddingProfileOperationWorker(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagDocumentRepository documentRepository,
            EmbeddingDispatchService dispatchService,
            EmbeddingProfileRegistry profileRegistry,
            DocumentProcessingRunService runService,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.dispatchService = dispatchService;
        this.profileRegistry = profileRegistry;
        this.runService = runService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${rag.long-running-operations.poll-interval-ms:1000}")
    public void processOneOperation() {
        Claim claim = transactionTemplate.execute(status -> claim());
        if (claim == null) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> process(claim));
        } catch (RuntimeException exception) {
            log.warn("Embedding Profile operation coordinator failed: operationId={}, type={}",
                    claim.operationId(), exception.getClass().getSimpleName());
            transactionTemplate.executeWithoutResult(status -> recordCoordinatorFailure(claim));
        }
    }

    private Claim claim() {
        UUID token = UUID.randomUUID();
        List<Claim> rows = jdbcTemplate.query("""
                UPDATE rag_long_running_operations operation
                SET status = 'RUNNING', lease_owner = ?, lease_token = ?,
                    lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp(),
                    next_retry_at = NULL
                WHERE operation.id = (
                    SELECT candidate.id
                    FROM rag_long_running_operations candidate
                    WHERE candidate.operation_kind = 'EMBEDDING_PROFILE_SWITCH'
                      AND candidate.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                      AND (candidate.status <> 'RETRY_WAIT'
                           OR candidate.next_retry_at <= clock_timestamp())
                      AND (candidate.lease_token IS NULL
                           OR candidate.lease_expires_at <= clock_timestamp())
                    ORDER BY candidate.created_at, candidate.id
                    LIMIT 1
                )
                  AND operation.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                  AND (operation.status <> 'RETRY_WAIT'
                       OR operation.next_retry_at <= clock_timestamp())
                  AND (operation.lease_token IS NULL
                       OR operation.lease_expires_at <= clock_timestamp())
                RETURNING operation.id, operation.lease_token
                """, (rs, rowNum) -> new Claim(
                rs.getObject("id", UUID.class),
                rs.getObject("lease_token", UUID.class)),
                workerId, token, LEASE_MILLIS);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void process(Claim claim) {
        Operation operation = readOperation(claim.operationId());
        if (operation == null || !claim.operationId().equals(operation.id())
                || !claim.leaseToken().equals(operation.leaseToken())) {
            return;
        }
        if (operation.cancelRequestedAt() != null) {
            convergeCancellation(operation);
            return;
        }
        Snapshot snapshot = snapshot(operation.progressPayload());
        EmbeddingProfile target = profileRegistry.findRequiredById(snapshot.targetProfileId());
        if (booleanValue(operation.progressPayload(), "retryRequested")) {
            if (!retryFailedChildren(operation)) {
                return;
            }
        }
        String phase = text(operation.progressPayload(), "phase", "SCAN");
        if ("WAIT_CHILDREN".equals(phase)) {
            awaitChildren(operation, snapshot, target);
            return;
        }
        scanPage(operation, snapshot, target);
    }

    private void scanPage(Operation operation, Snapshot snapshot, EmbeddingProfile target) {
        List<Long> documentIds = jdbcTemplate.query("""
                SELECT id FROM rag_documents
                WHERE collection_id = ? AND enabled = TRUE AND id > ?
                ORDER BY id LIMIT ?
                """, (rs, rowNum) -> rs.getLong(1),
                operation.collectionId(), operation.cursorDocumentId(), PAGE_SIZE);
        for (Long documentId : documentIds) {
            if (cancelRequested(operation.id())) {
                return;
            }
            var document = documentRepository.findById(documentId).orElse(null);
            if (document == null || !Boolean.TRUE.equals(document.getEnabled())) {
                continue;
            }
            var result = dispatchService.enqueueForProfileInCurrentTransaction(
                    document, target, false, false, "API",
                    operation.ownerPrincipalId());
            if (result.embeddingJobId() == null) {
                continue;
            }
            UUID runId = jdbcTemplate.queryForObject("""
                    SELECT id FROM rag_document_processing_runs
                    WHERE embedding_job_id = ?
                    """, UUID.class, result.embeddingJobId());
            if (runId != null) {
                jdbcTemplate.update("""
                        INSERT INTO rag_long_running_operation_run_refs (
                            operation_id, run_id, created_by_operation)
                        VALUES (?, ?, ?)
                        ON CONFLICT (operation_id, run_id) DO NOTHING
                        """, operation.id(), runId,
                        result.action() == EmbeddingAction.ASYNC_QUEUED);
            }
        }
        if (!ownsLease(operation.id(), operation.leaseToken())) {
            return;
        }
        long cursor = documentIds.isEmpty()
                ? operation.cursorDocumentId() : documentIds.getLast();
        String nextPhase = documentIds.size() < PAGE_SIZE ? "WAIT_CHILDREN" : "SCAN";
        int updated = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET cursor_document_id = ?, cursor_phase = ?,
                    processed_count = processed_count + ?,
                    progress_payload = jsonb_set(
                        progress_payload || jsonb_build_object(
                            'phase', ?::text,
                            'documentsScanned', processed_count + ?),
                        '{retryRequested}', 'false'::jsonb, TRUE),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                  AND cancel_requested_at IS NULL
                """, cursor, nextPhase, documentIds.size(), nextPhase, documentIds.size(),
                operation.id(), operation.leaseToken());
        if (updated != 1) {
            throw new IllegalStateException("Profile operation lease was lost during page commit");
        }
    }

    private void awaitChildren(
            Operation operation,
            Snapshot snapshot,
            EmbeddingProfile target) {
        if (cancelRequested(operation.id())) {
            convergeCancellation(readOperation(operation.id()));
            return;
        }
        Counts counts = childCounts(operation.id());
        if (counts.active() > 0) {
            releaseLease(operation);
            return;
        }
        if (counts.failed() > 0) {
            markDegraded(operation, "PROFILE_CHILD_RUN_FAILED");
            return;
        }
        String vectorColumn = EmbeddingVectorColumns.columnFor(target.dimensions());
        Long missing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rag_documents document
                WHERE document.collection_id = ? AND document.enabled = TRUE
                  AND NOT EXISTS (
                    SELECT 1
                    FROM rag_document_embedding_state state
                    WHERE state.document_id = document.id
                      AND state.embedding_profile_id = ?
                      AND state.status = 'COMPLETED'
                      AND state.content_hash = document.content_hash
                      AND state.chunk_count = (
                          SELECT COUNT(*) FROM rag_embeddings embedding
                          WHERE embedding.document_id = document.id
                            AND embedding.embedding_profile_id = ?
                            AND embedding.%s IS NOT NULL)
                  )
                """.formatted(vectorColumn), Long.class,
                operation.collectionId(), target.id(), target.id());
        if (missing != null && missing > 0) {
            jdbcTemplate.update("""
                    UPDATE rag_long_running_operations
                    SET cursor_document_id = 0, cursor_phase = 'SCAN',
                        progress_payload = progress_payload || '{"phase":"SCAN"}'::jsonb,
                        lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                        heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                      AND cancel_requested_at IS NULL
                    """, operation.id(), operation.leaseToken());
            return;
        }
        switchCollection(operation, snapshot, target);
    }

    private void switchCollection(
            Operation operation,
            Snapshot snapshot,
            EmbeddingProfile target) {
        int held = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                  AND cancel_requested_at IS NULL
                """, operation.id(), operation.leaseToken());
        if (held != 1) {
            throw new IllegalStateException("Profile switch lost its operation fence");
        }
        CollectionVersion current = jdbcTemplate.queryForObject("""
                SELECT version, active_embedding_profile_id
                FROM rag_collection WHERE id = ? AND deleted = FALSE AND purged_at IS NULL
                """, (rs, rowNum) -> new CollectionVersion(
                rs.getLong("version"), rs.getLong("active_embedding_profile_id")),
                operation.collectionId());
        if (current == null || current.profileId() != snapshot.sourceProfileId()) {
            markStale(operation, "SOURCE_PROFILE_CHANGED");
            return;
        }
        int switched = jdbcTemplate.update("""
                UPDATE rag_collection
                SET active_embedding_profile_id = ?, embedding_model = ?,
                    dimensions = ?, version = version + 1,
                    updated_at = clock_timestamp()
                WHERE id = ? AND version = ?
                  AND active_embedding_profile_id = ?
                  AND deleted = FALSE AND purged_at IS NULL
                """, target.id(), target.modelName(), target.dimensions(),
                operation.collectionId(), current.version(), snapshot.sourceProfileId());
        if (switched != 1) {
            jdbcTemplate.update("""
                    UPDATE rag_long_running_operations
                    SET cursor_document_id = 0, cursor_phase = 'SCAN',
                        progress_payload = progress_payload || '{"phase":"SCAN"}'::jsonb,
                        lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                        heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                    """, operation.id(), operation.leaseToken());
            return;
        }
        String result = "{\"status\":\"SUCCEEDED\",\"collectionKey\":\""
                + escape(operation.collectionKey()) + "\",\"embeddingProfileKey\":\""
                + escape(target.profileKey()) + "\"}";
        jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = 'SUCCEEDED', terminal_reason = 'PROFILE_SWITCHED',
                    result_payload = ?::jsonb, finished_at = clock_timestamp(),
                    result_expires_at = clock_timestamp() + INTERVAL '7 days',
                    tombstone_expires_at = GREATEST(
                        created_at + INTERVAL '30 days',
                        clock_timestamp() + INTERVAL '7 days'),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    progress_payload = progress_payload || '{"phase":"COMPLETED"}'::jsonb,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, result, operation.id(), operation.leaseToken());
        releaseReservations(operation, true);
    }

    private void convergeCancellation(Operation operation) {
        if (operation == null) {
            return;
        }
        List<UUID> cancellable = jdbcTemplate.query("""
                SELECT reference.run_id
                FROM rag_long_running_operation_run_refs reference
                JOIN rag_document_processing_runs run ON run.id = reference.run_id
                WHERE reference.operation_id = ? AND reference.created_by_operation = TRUE
                  AND run.status IN ('QUEUED', 'RUNNING')
                  AND NOT EXISTS (
                    SELECT 1
                    FROM rag_long_running_operation_run_refs shared
                    JOIN rag_long_running_operations parent ON parent.id = shared.operation_id
                    WHERE shared.run_id = reference.run_id
                      AND shared.operation_id <> reference.operation_id
                      AND parent.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'DEGRADED'))
                ORDER BY run.document_id, run.attempt_no
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), operation.id());
        for (UUID runId : cancellable) {
            try {
                runService.cancel(runId);
            } catch (RagException exception) {
                log.debug("Processing Run cancellation is already converging: runId={}, code={}",
                        runId, exception.getErrorCode());
            }
        }
        long activeOwned = activeUnsharedChildren(operation.id());
        if (activeOwned > 0) {
            releaseLease(operation);
            return;
        }
        int cancelled = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = 'CANCELLED', terminal_reason = 'CLIENT_CANCELLED',
                    result_payload = '{"status":"CANCELLED"}'::jsonb,
                    finished_at = clock_timestamp(),
                    result_expires_at = clock_timestamp() + INTERVAL '7 days',
                    tombstone_expires_at = GREATEST(
                        created_at + INTERVAL '30 days',
                        clock_timestamp() + INTERVAL '7 days'),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    progress_payload = progress_payload || '{"phase":"CANCELLED"}'::jsonb,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING'
                  AND cancel_requested_at IS NOT NULL
                  AND lease_token = ?
                """, operation.id(), operation.leaseToken());
        if (cancelled == 1) {
            releaseReservations(operation, true);
        }
    }

    private void recordCoordinatorFailure(Claim claim) {
        Operation operation = readOperation(claim.operationId());
        if (operation == null || !claim.leaseToken().equals(operation.leaseToken())) {
            return;
        }
        if (operation.attemptCount() + 1 >= MAX_COORDINATOR_ATTEMPTS) {
            markDegraded(operation, "OPERATION_COORDINATOR_RETRIES_EXHAUSTED");
            return;
        }
        jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = 'RETRY_WAIT', attempt_count = attempt_count + 1,
                    next_retry_at = clock_timestamp() + INTERVAL '10 seconds',
                    error_code = 'OPERATION_COORDINATOR_RETRYABLE',
                    error_payload = '{"category":"coordinator"}'::jsonb,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, operation.id(), operation.leaseToken());
    }

    private void markDegraded(Operation operation, String errorCode) {
        int changed = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = 'DEGRADED', error_code = ?,
                    error_payload = jsonb_build_object('category', 'operation'),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    next_retry_at = NULL, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, errorCode, operation.id(), operation.leaseToken());
        if (changed == 1) {
            jdbcTemplate.update("""
                    UPDATE rag_long_running_operation_global_capacity
                    SET active_count = GREATEST(0, active_count - 1),
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                    """);
        }
    }

    private void markStale(Operation operation, String reason) {
        int changed = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = 'STALE', terminal_reason = ?,
                    result_payload = jsonb_build_object('status', 'STALE', 'reason', ?),
                    finished_at = clock_timestamp(),
                    result_expires_at = clock_timestamp() + INTERVAL '7 days',
                    tombstone_expires_at = GREATEST(
                        created_at + INTERVAL '30 days',
                        clock_timestamp() + INTERVAL '7 days'),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, reason, reason, operation.id(), operation.leaseToken());
        if (changed == 1) {
            releaseReservations(operation, true);
        }
    }

    private void releaseLease(Operation operation) {
        jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, operation.id(), operation.leaseToken());
    }

    private void releaseReservations(Operation operation, boolean releaseGlobal) {
        jdbcTemplate.update("""
                UPDATE rag_long_running_operation_owner_capacity
                SET active_count = GREATEST(0, active_count - 1),
                    updated_at = clock_timestamp()
                WHERE owner_principal_id = ?
                """, operation.ownerPrincipalId());
        if (releaseGlobal) {
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
                """, operation.collectionId(), operation.id());
    }

    private Counts childCounts(UUID operationId) {
        return jdbcTemplate.queryForObject("""
                WITH latest AS (
                    SELECT DISTINCT ON (run.document_id) run.status
                    FROM rag_long_running_operation_run_refs reference
                    JOIN rag_document_processing_runs run ON run.id = reference.run_id
                    WHERE reference.operation_id = ?
                    ORDER BY run.document_id, run.attempt_no DESC, run.created_at DESC
                )
                SELECT COUNT(*) FILTER (WHERE status IN ('QUEUED', 'RUNNING')) AS active,
                       COUNT(*) FILTER (WHERE status IN ('FAILED', 'DEAD_LETTERED', 'CANCELLED', 'STALE')) AS failed,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed
                FROM latest
                """, (rs, rowNum) -> new Counts(
                rs.getLong("active"), rs.getLong("failed"), rs.getLong("completed")), operationId);
    }

    private long activeUnsharedChildren(UUID operationId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_long_running_operation_run_refs reference
                JOIN rag_document_processing_runs run ON run.id = reference.run_id
                WHERE reference.operation_id = ? AND reference.created_by_operation = TRUE
                  AND run.status IN ('QUEUED', 'RUNNING')
                  AND NOT EXISTS (
                    SELECT 1
                    FROM rag_long_running_operation_run_refs shared
                    JOIN rag_long_running_operations parent ON parent.id = shared.operation_id
                    WHERE shared.run_id = reference.run_id
                      AND shared.operation_id <> reference.operation_id
                      AND parent.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'DEGRADED'))
                """, Long.class, operationId);
        return count == null ? 0 : count;
    }

    private boolean ownsLease(UUID operationId, UUID token) {
        Boolean owned = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM rag_long_running_operations
                    WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                      AND cancel_requested_at IS NULL
                      AND lease_expires_at > clock_timestamp())
                """, Boolean.class, operationId, token);
        return Boolean.TRUE.equals(owned);
    }

    private boolean cancelRequested(UUID operationId) {
        Boolean requested = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM rag_long_running_operations
                    WHERE id = ? AND cancel_requested_at IS NOT NULL)
                """, Boolean.class, operationId);
        return Boolean.TRUE.equals(requested);
    }

    private boolean retryFailedChildren(Operation operation) {
        List<UUID> failedRuns = jdbcTemplate.query("""
                SELECT run.id
                FROM rag_long_running_operation_run_refs reference
                JOIN rag_document_processing_runs run ON run.id = reference.run_id
                WHERE reference.operation_id = ? AND reference.created_by_operation = TRUE
                  AND run.status IN ('FAILED', 'DEAD_LETTERED')
                  AND run.id = (
                    SELECT latest.id FROM rag_document_processing_runs latest
                    WHERE latest.document_id = run.document_id
                      AND latest.embedding_profile_id = run.embedding_profile_id
                    ORDER BY latest.attempt_no DESC, latest.created_at DESC
                    LIMIT 1)
                ORDER BY run.document_id, run.attempt_no
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), operation.id());
        for (UUID runId : failedRuns) {
            try {
                var retry = runService.retry(runId, null);
                if (retry.embeddingJobId() != null) {
                    jdbcTemplate.update("""
                            INSERT INTO rag_long_running_operation_run_refs (
                                operation_id, run_id, created_by_operation)
                            VALUES (?, ?, ?)
                            ON CONFLICT (operation_id, run_id) DO NOTHING
                            """, operation.id(), retry.runId(),
                            !Boolean.TRUE.equals(retry.coalesced()));
                }
            } catch (RagException exception) {
                markDegraded(operation, "PROFILE_CHILD_RUN_NOT_RETRYABLE");
                return false;
            }
        }
        jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET progress_payload = jsonb_set(
                        progress_payload, '{retryRequested}', 'false'::jsonb, TRUE),
                    cursor_document_id = 0, cursor_phase = 'SCAN',
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, operation.id(), operation.leaseToken());
        return false;
    }

    private boolean booleanValue(String json, String key) {
        try {
            return objectMapper.readTree(json).path(key).asBoolean(false);
        } catch (Exception exception) {
            throw new IllegalStateException("Profile operation progress is invalid", exception);
        }
    }

    private Operation readOperation(UUID operationId) {
        List<Operation> rows = jdbcTemplate.query("""
                SELECT operation.id AS operation_id, owner_principal_id,
                       operation.collection_id, collection_key,
                       operation_kind, status, progress_payload, cursor_document_id,
                       cursor_phase, attempt_count, lease_token, cancel_requested_at,
                       purge_fence_completed_at
                FROM rag_long_running_operations operation
                JOIN rag_collection collection ON collection.id = operation.collection_id
                WHERE operation.id = ?
                """, (rs, rowNum) -> new Operation(
                rs.getObject("operation_id", UUID.class), rs.getString("owner_principal_id"),
                rs.getLong("collection_id"), rs.getString("collection_key"),
                rs.getString("operation_kind"), rs.getString("status"),
                rs.getString("progress_payload"), rs.getLong("cursor_document_id"),
                rs.getString("cursor_phase"), rs.getInt("attempt_count"),
                rs.getObject("lease_token", UUID.class),
                rs.getObject("cancel_requested_at", OffsetDateTime.class),
                rs.getObject("purge_fence_completed_at", OffsetDateTime.class)), operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Snapshot snapshot(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new Snapshot(node.path("sourceProfileId").asLong(),
                    node.path("targetProfileId").asLong());
        } catch (Exception exception) {
            throw new IllegalStateException("Profile operation snapshot is invalid", exception);
        }
    }

    private String text(String json, String key, String fallback) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path(key).asText(fallback);
        } catch (Exception exception) {
            throw new IllegalStateException("Profile operation progress is invalid", exception);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Claim(UUID operationId, UUID leaseToken) {
    }

    private record Snapshot(long sourceProfileId, long targetProfileId) {
    }

    private record Counts(long active, long failed, long completed) {
    }

    private record CollectionVersion(long version, long profileId) {
    }

    private record Operation(
            UUID id,
            String ownerPrincipalId,
            long collectionId,
            String collectionKey,
            String operationKind,
            String status,
            String progressPayload,
            long cursorDocumentId,
            String cursorPhase,
            int attemptCount,
            UUID leaseToken,
            OffsetDateTime cancelRequestedAt,
            OffsetDateTime purgeFenceCompletedAt) {
    }
}
