package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertRequest;
import com.springairag.api.dto.DocumentSyncRunBatchUpsertResponse;
import com.springairag.api.dto.DocumentSyncRunBeginRequest;
import com.springairag.api.dto.DocumentSyncRunCompleteRequest;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.dto.DocumentSyncRunItemResponse;
import com.springairag.api.dto.DocumentSyncRunPreviewResponse;
import com.springairag.api.dto.DocumentSyncRunResponse;
import com.springairag.api.dto.DocumentSyncRunStatusResponse;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncMissingPolicy;
import com.springairag.api.enums.DocumentSyncRunStatus;
import com.springairag.api.enums.DocumentSyncSnapshotMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagDocumentLifecycleProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative external-source snapshot reconciliation.
 *
 * <p>This service stores only run/item identity and fingerprints. Document
 * bodies, JSONB payloads and lease tokens never enter the reconciliation
 * ledger. All exclusion and deletion decisions are protected by the source
 * mutation sequence allocated with conditional DML.
 */
@Service
public class DocumentSyncRunService {

    private static final String DEFAULT_NAMESPACE = "default";
    private static final int MAX_PREVIEW_IDENTITIES = 10_000;
    private static final int MAX_RESPONSE_IDENTITIES = 1_000;
    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final DocumentMutationService mutationService;
    private final RagDocumentLifecycleProperties properties;
    private final TransactionTemplate transactionTemplate;

    public DocumentSyncRunService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CollectionIdentityResolver collectionIdentityResolver,
            DocumentMutationService mutationService,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.mutationService = mutationService;
        this.properties = ragProperties.getDocumentLifecycle();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public DocumentSyncRunResponse begin(
            DocumentSyncRunBeginRequest request,
            String leaseToken) {
        requireEnabled();
        Objects.requireNonNull(request, "request");
        String token = requireLeaseToken(leaseToken);
        String collectionKey = requireVisible(
                request.collectionKey(), "collectionKey", 128);
        String namespace = normalizeNamespace(request.sourceNamespace());
        validateMode(
                request.snapshotMode(),
                request.missingPolicy(),
                request.confirmExclusiveOffline());
        String clientRunId = requireVisible(
                request.clientRunId(), "clientRunId", 255);
        RagCollection collection = requireWritableCollection(collectionKey);
        String tokenHash = hashToken(token);

        try {
            return requireResult(transactionTemplate.execute(status -> {
                expireActiveRuns(collection.getId(), namespace);
                RunRow existing = findByClientRun(
                        collection.getId(), namespace, clientRunId);
                if (existing != null) {
                    if (!Objects.equals(existing.leaseTokenHash(), tokenHash)
                            || !sameBeginRequest(existing, request, namespace)) {
                        throw new RagException(
                                ErrorCode.SYNC_RUN_LEASE_CONFLICT,
                                "clientRunId is already bound to another sync run");
                    }
                    return toResponse(existing, collectionKey);
                }

                RunRow active = findActive(collection.getId(), namespace);
                if (active != null) {
                    throw new RagException(
                            ErrorCode.ACTIVE_SYNC_RUN_EXISTS,
                            "An active sync run already exists for this collection and sourceNamespace");
                }

                UUID runId = UUID.randomUUID();
                long snapshotStartSequence =
                        mutationService.allocateSourceSequenceForSnapshot(
                                collection.getId(), namespace);
                int leaseSeconds = request.effectiveLeaseSeconds();
                jdbcTemplate.update("""
                        INSERT INTO rag_document_sync_runs (
                            id, collection_id, source_namespace, client_run_id,
                            lease_token_hash, sync_generation,
                            snapshot_start_sequence, snapshot_mode,
                            missing_policy, status, lease_expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE',
                            CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                        """,
                        runId,
                        collection.getId(),
                        namespace,
                        clientRunId,
                        tokenHash,
                        snapshotStartSequence,
                        snapshotStartSequence,
                        request.snapshotMode().name(),
                        request.missingPolicy().name(),
                        leaseSeconds);
                return toResponse(
                        requireRun(runId, collection.getId(), namespace),
                        collectionKey);
            }));
        } catch (DataIntegrityViolationException e) {
            throw new RagException(
                    ErrorCode.ACTIVE_SYNC_RUN_EXISTS,
                    "Another sync run was created concurrently");
        }
    }

    public DocumentSyncRunBatchUpsertResponse batchUpsert(
            UUID runId,
            String leaseToken,
            DocumentSyncRunBatchUpsertRequest request) {
        requireEnabled();
        String token = requireLeaseToken(leaseToken);
        Objects.requireNonNull(request, "request");
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        String tokenHash = hashToken(token);
        List<DocumentSyncRunItemResponse> results = new ArrayList<>(
                request.items().size());
        for (DocumentSyncRunItemRequest item : request.items()) {
            try {
                results.add(requireResult(transactionTemplate.execute(status ->
                        applyItem(runId, tokenHash, item))));
            } catch (RagException e) {
                if (isRunControlError(e.getErrorCodeEnum())) {
                    throw e;
                }
                results.add(recordFailedItem(runId, tokenHash, item, e));
            } catch (RuntimeException e) {
                results.add(recordFailedItem(runId, tokenHash, item, e));
            }
        }
        return new DocumentSyncRunBatchUpsertResponse(
                runId.toString(),
                results,
                new DocumentSyncRunBatchUpsertResponse.Summary(
                        results.size(),
                        count(results, DocumentSyncItemStatus.APPLIED),
                        count(results, DocumentSyncItemStatus.UNCHANGED),
                        count(results, DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION),
                        count(results, DocumentSyncItemStatus.FAILED)));
    }

    public DocumentSyncRunPreviewResponse preview(
            UUID runId,
            String leaseToken) {
        requireEnabled();
        String token = requireLeaseToken(leaseToken);
        String tokenHash = hashToken(token);
        return requireResult(transactionTemplate.execute(status -> {
            RunRow run = requireActiveLease(runId, tokenHash);
            CandidateSet candidates = findCandidates(run);
            String previewToken = UUID.randomUUID() + "." + UUID.randomUUID();
            String previewTokenHash = hashToken(previewToken);
            int updated = jdbcTemplate.update("""
                    UPDATE rag_document_sync_runs
                    SET preview_token_hash = ?, preview_fingerprint = ?,
                        preview_missing_count = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND lease_token_hash = ? AND status = 'ACTIVE'
                      AND lease_expires_at >= CURRENT_TIMESTAMP
                    """,
                    previewTokenHash,
                    candidates.fingerprint(),
                    candidates.candidates().size(),
                    run.id(),
                    tokenHash);
            if (updated != 1) {
                throw invalidState("Sync run lease was lost while previewing");
            }
            return new DocumentSyncRunPreviewResponse(
                    run.id().toString(),
                    previewToken,
                    candidates.fingerprint(),
                    candidates.candidates().size(),
                    candidates.textCount(),
                    candidates.jsonCount(),
                    candidates.protectedCount(),
                    candidates.unresolvedLegacyCount(),
                    candidates.candidates().stream()
                            .limit(MAX_RESPONSE_IDENTITIES)
                            .map(candidate -> new DocumentSyncRunPreviewResponse.IdentitySummary(
                                    candidate.externalId(),
                                    candidate.documentKind().name(),
                                    candidate.sourceRevision()))
                            .toList());
        }));
    }

    public DocumentSyncRunResponse complete(
            UUID runId,
            String leaseToken,
            DocumentSyncRunCompleteRequest request) {
        requireEnabled();
        String token = requireLeaseToken(leaseToken);
        Objects.requireNonNull(request, "request");
        String tokenHash = hashToken(token);
        return requireResult(transactionTemplate.execute(status -> {
            RunRow run = requireRun(runId);
            requireToken(run, tokenHash);
            requireCollectionAccess(run.collectionId());
            if (run.status() == DocumentSyncRunStatus.COMPLETED) {
                return toResponse(run, collectionKey(run.collectionId()));
            }
            if (run.status() != DocumentSyncRunStatus.ACTIVE) {
                throw invalidState("Only an ACTIVE sync run can complete");
            }
            expireIfNeeded(run);
            run = requireActiveLease(runId, tokenHash);
            if (run.previewTokenHash() == null
                    || !Objects.equals(
                            run.previewTokenHash(), hashToken(request.previewToken()))) {
                throw new RagException(
                        ErrorCode.SYNC_RUN_PREVIEW_CONFLICT,
                        "previewToken does not belong to this active sync run");
            }
            if (run.missingPolicy() == DocumentSyncMissingPolicy.TOMBSTONE
                    && hasFailedItems(run.id())) {
                throw new RagException(
                        ErrorCode.SYNC_RUN_INCOMPLETE,
                        "Retry or remove all failed snapshot items before completing a tombstone run");
            }

            long completeSequence =
                    mutationService.allocateSourceSequenceForSnapshot(
                            run.collectionId(), run.sourceNamespace());
            CandidateSet candidates = findCandidates(run);
            if (!Objects.equals(
                    run.previewFingerprint(), candidates.fingerprint())) {
                throw new RagException(
                        ErrorCode.SYNC_RUN_PREVIEW_CONFLICT,
                        "The missing set changed after preview; preview again");
            }

            int tombstoned = 0;
            if (run.missingPolicy() == DocumentSyncMissingPolicy.TOMBSTONE) {
                long activeCount = countActiveExternal(run);
                long threshold = Math.min(
                        properties.getSyncRunMaxMissingAbsolute(),
                        Math.max(
                                1L,
                                (long) Math.ceil(activeCount
                                        * properties.getSyncRunMaxMissingPercent()
                                        / 100.0)));
                int confirmedMissingCount =
                        request.effectiveConfirmMissingCount();
                int candidateCount = candidates.candidates().size();
                if (confirmedMissingCount >= 0
                        && confirmedMissingCount != candidateCount) {
                    throw new RagException(
                            ErrorCode.SYNC_RUN_DELETE_PROTECTION,
                            "confirmMissingCount must equal the previewed missing count");
                }
                if (candidateCount > threshold && confirmedMissingCount < 0) {
                    throw new RagException(
                            ErrorCode.SYNC_RUN_DELETE_PROTECTION,
                            "Missing count exceeds the configured deletion protection threshold");
                }
                for (Candidate candidate : candidates.candidates()) {
                    if (mutationService.reconcileMissingExternal(
                            candidate.documentId(),
                            run.id(),
                            run.snapshotStartSequence())) {
                        tombstoned++;
                    }
                }
            }

            int updated = jdbcTemplate.update("""
                    UPDATE rag_document_sync_runs
                    SET status = 'COMPLETED',
                        complete_sequence = ?,
                        tombstoned_count = tombstoned_count + ?,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND lease_token_hash = ? AND status = 'ACTIVE'
                      AND lease_expires_at >= CURRENT_TIMESTAMP
                    """,
                    completeSequence,
                    tombstoned,
                    run.id(),
                    tokenHash);
            if (updated != 1) {
                throw invalidState("Sync run lease was lost while completing");
            }
            return toResponse(
                    requireRun(run.id(), run.collectionId(), run.sourceNamespace()),
                    collectionKey(run.collectionId()));
        }));
    }

    public DocumentSyncRunResponse abort(
            UUID runId,
            String leaseToken) {
        requireEnabled();
        String token = requireLeaseToken(leaseToken);
        String tokenHash = hashToken(token);
        return requireResult(transactionTemplate.execute(status -> {
            RunRow run = requireRun(runId);
            requireToken(run, tokenHash);
            requireCollectionAccess(run.collectionId());
            if (run.status() == DocumentSyncRunStatus.ABORTED
                    || run.status() == DocumentSyncRunStatus.EXPIRED
                    || run.status() == DocumentSyncRunStatus.COMPLETED) {
                return toResponse(run, collectionKey(run.collectionId()));
            }
            expireIfNeeded(run);
            int updated = jdbcTemplate.update("""
                    UPDATE rag_document_sync_runs
                    SET status = 'ABORTED', aborted_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND lease_token_hash = ?
                      AND status = 'ACTIVE'
                      AND lease_expires_at >= CURRENT_TIMESTAMP
                    """,
                    run.id(), tokenHash);
            if (updated != 1) {
                throw invalidState("Sync run lease was lost while aborting");
            }
            return toResponse(
                    requireRun(run.id(), run.collectionId(), run.sourceNamespace()),
                    collectionKey(run.collectionId()));
        }));
    }

    public DocumentSyncRunResponse get(
            UUID runId,
            String collectionKey,
            String sourceNamespace) {
        requireEnabled();
        RagCollection collection = requireReadableCollection(collectionKey);
        return toResponse(
                requireRun(
                        runId,
                        collection.getId(),
                        normalizeNamespace(sourceNamespace)),
                collection.getCollectionKey());
    }

    public DocumentSyncRunStatusResponse list(
            String collectionKey,
            String sourceNamespace,
            int page,
            int size) {
        requireEnabled();
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
        }
        RagCollection collection = requireReadableCollection(collectionKey);
        String namespace = sourceNamespace == null
                ? null : normalizeNamespace(sourceNamespace);
        String countSql = """
                SELECT COUNT(*) FROM rag_document_sync_runs
                WHERE collection_id = ?
                  AND (? IS NULL OR source_namespace = ?)
                """;
        long total = jdbcTemplate.queryForObject(
                countSql,
                Long.class,
                collection.getId(),
                namespace,
                namespace);
        List<RunRow> rows = jdbcTemplate.query("""
                SELECT id, collection_id, source_namespace, client_run_id,
                       lease_token_hash, sync_generation,
                       snapshot_start_sequence, complete_sequence,
                       snapshot_mode, missing_policy, status,
                       lease_expires_at, preview_token_hash,
                       preview_fingerprint, preview_missing_count,
                       applied_count, unchanged_count, skipped_count,
                       failed_count, tombstoned_count
                FROM rag_document_sync_runs
                WHERE collection_id = ?
                  AND (? IS NULL OR source_namespace = ?)
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapRun,
                collection.getId(),
                namespace,
                namespace,
                size,
                page * size);
        return new DocumentSyncRunStatusResponse(
                rows.stream()
                        .map(row -> toResponse(row, collection.getCollectionKey()))
                        .toList(),
                total,
                page,
                size);
    }

    private DocumentSyncRunItemResponse applyItem(
            UUID runId,
            String tokenHash,
            DocumentSyncRunItemRequest item) {
        RunRow run = requireActiveLease(runId, tokenHash);
        String externalId = requireVisible(item.externalId(), "externalId", 255);
        String fingerprint = fingerprint(item);
        LedgerRow existing = findItem(runId, externalId);
        boolean reopenedFailedItem = false;
        if (existing != null) {
            if (!Objects.equals(existing.itemFingerprint(), fingerprint)
                    || !Objects.equals(
                            existing.documentKind().name(),
                            item.documentKind().name())
                    || !Objects.equals(existing.sourceRevision(), item.sourceRevision())) {
                throw new RagException(
                        ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                        "The same externalId was already used with different item data");
            }
            if (existing.status() == DocumentSyncItemStatus.FAILED) {
                int reopened = jdbcTemplate.update("""
                        UPDATE rag_document_sync_run_items
                        SET status = 'FAILED',
                            error_code = 'SYNC_RUN_ITEM_IN_PROGRESS',
                            error_message = 'Item is being processed',
                            seen_at = CURRENT_TIMESTAMP
                        WHERE run_id = ? AND external_id = ?
                          AND item_fingerprint = ?
                          AND status = 'FAILED'
                        """,
                        runId,
                        externalId,
                        fingerprint);
                if (reopened != 1) {
                    throw new RagException(
                            ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                            "The same sync-run item is currently being processed");
                }
                existing = findItem(runId, externalId);
                reopenedFailedItem = true;
            }
            if (!"SYNC_RUN_ITEM_IN_PROGRESS".equals(existing.errorCode())) {
                return toItemResponse(existing);
            }
            if (!reopenedFailedItem) {
                throw new RagException(
                        ErrorCode.SYNC_RUN_ITEM_CONFLICT,
                        "The same sync-run item is currently being processed");
            }
        }

        if (existing == null) {
            jdbcTemplate.update("""
                    INSERT INTO rag_document_sync_run_items (
                        run_id, external_id, document_kind, item_fingerprint,
                        source_revision, status, error_code, error_message
                    ) VALUES (?, ?, ?, ?, ?, 'FAILED',
                        'SYNC_RUN_ITEM_IN_PROGRESS', 'Item is being processed')
                    """,
                    runId,
                    externalId,
                    item.documentKind().name(),
                    fingerprint,
                    item.sourceRevision());
        }

        DocumentMutationService.SyncItemMutation mutation =
                mutationService.upsertSyncRunItemInCurrentTransaction(
                        run.collectionId(),
                        collectionKey(run.collectionId()),
                        run.sourceNamespace(),
                        item,
                        run.snapshotStartSequence());
        if (mutation.status() != DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION
                && mutation.documentId() != null) {
            jdbcTemplate.update("""
                    UPDATE rag_documents
                    SET last_seen_sync_run_id = ?,
                        last_seen_sync_generation = ?
                    WHERE id = ?
                    """,
                    run.id(),
                    run.syncGeneration(),
                    mutation.documentId());
        }
        jdbcTemplate.update("""
                UPDATE rag_document_sync_run_items
                SET document_id = ?, status = ?, error_code = ?,
                    error_message = ?, seen_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND external_id = ?
                """,
                mutation.documentId(),
                mutation.status().name(),
                mutation.errorCode(),
                truncate(mutation.error()),
                run.id(),
                externalId);
        incrementRunCount(run, mutation.status());
        return new DocumentSyncRunItemResponse(
                externalId,
                item.documentKind(),
                mutation.status(),
                mutation.documentId(),
                mutation.sourceRevision(),
                mutation.errorCode(),
                truncate(mutation.error()),
                mutation.embeddingAction(),
                mutation.embeddingJobId());
    }

    private DocumentSyncRunItemResponse recordFailedItem(
            UUID runId,
            String tokenHash,
            DocumentSyncRunItemRequest item,
            RuntimeException error) {
        return requireResult(transactionTemplate.execute(status -> {
            RunRow run = requireActiveLease(runId, tokenHash);
            String externalId = requireVisible(
                    item.externalId(), "externalId", 255);
            String code = errorCode(error);
            String message = truncate(error.getMessage());
            LedgerRow existing = findItem(runId, externalId);
            if (existing != null
                    && !"SYNC_RUN_ITEM_IN_PROGRESS".equals(existing.errorCode())) {
                return toItemResponse(existing);
            }
            if (existing == null) {
                jdbcTemplate.update("""
                        INSERT INTO rag_document_sync_run_items (
                            run_id, external_id, document_kind,
                            item_fingerprint, source_revision, status,
                            error_code, error_message
                        ) VALUES (?, ?, ?, ?, ?, 'FAILED', ?, ?)
                        """,
                        runId,
                        externalId,
                        item.documentKind().name(),
                        fingerprint(item),
                        item.sourceRevision(),
                        code,
                        message);
            } else {
                jdbcTemplate.update("""
                        UPDATE rag_document_sync_run_items
                        SET status = 'FAILED', error_code = ?,
                            error_message = ?, seen_at = CURRENT_TIMESTAMP
                        WHERE run_id = ? AND external_id = ?
                        """,
                        code,
                        message,
                        runId,
                        externalId);
            }
            incrementRunCount(run, DocumentSyncItemStatus.FAILED);
            return new DocumentSyncRunItemResponse(
                    externalId,
                    item.documentKind(),
                    DocumentSyncItemStatus.FAILED,
                    null,
                    item.sourceRevision(),
                    code,
                    message,
                    "NONE",
                    null);
        }));
    }

    private void expireActiveRuns(long collectionId, String namespace) {
        jdbcTemplate.update("""
                UPDATE rag_document_sync_runs
                SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
                WHERE collection_id = ? AND source_namespace = ?
                  AND status = 'ACTIVE'
                  AND lease_expires_at < CURRENT_TIMESTAMP
                """,
                collectionId,
                namespace);
    }

    private void expireIfNeeded(RunRow run) {
        if (run.status() == DocumentSyncRunStatus.ACTIVE
                && run.leaseExpiresAt().isBefore(OffsetDateTime.now())) {
            jdbcTemplate.update("""
                    UPDATE rag_document_sync_runs
                    SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'ACTIVE'
                      AND lease_expires_at < CURRENT_TIMESTAMP
                    """,
                    run.id());
            throw invalidState("Sync run lease has expired");
        }
    }

    private RunRow requireActiveLease(UUID runId, String tokenHash) {
        RunRow run = requireRun(runId);
        requireToken(run, tokenHash);
        requireCollectionAccess(run.collectionId());
        expireIfNeeded(run);
        if (run.status() != DocumentSyncRunStatus.ACTIVE) {
            throw invalidState("Sync run is not ACTIVE");
        }
        return run;
    }

    private void requireToken(RunRow run, String tokenHash) {
        if (!Objects.equals(run.leaseTokenHash(), tokenHash)) {
            throw new RagException(
                    ErrorCode.SYNC_RUN_LEASE_CONFLICT,
                    "Sync run lease token is invalid");
        }
    }

    private CandidateSet findCandidates(RunRow run) {
        Integer candidateCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rag_documents
                WHERE collection_id = ? AND source_namespace = ?
                  AND external_id IS NOT NULL
                  AND source_revision IS NOT NULL
                  AND enabled = true
                  AND source_mutation_sequence <= ?
                  AND (last_seen_sync_run_id IS NULL OR last_seen_sync_run_id <> ?)
                """,
                Integer.class,
                run.collectionId(),
                run.sourceNamespace(),
                run.snapshotStartSequence(),
                run.id());
        int count = candidateCount == null ? 0 : candidateCount;
        if (count > MAX_PREVIEW_IDENTITIES) {
            throw new RagException(
                    ErrorCode.SYNC_RUN_DELETE_PROTECTION,
                    "Missing candidate set exceeds the preview safety bound");
        }
        List<Candidate> candidates = jdbcTemplate.query("""
                SELECT id, external_id, document_type, source_revision
                FROM rag_documents
                WHERE collection_id = ? AND source_namespace = ?
                  AND external_id IS NOT NULL
                  AND source_revision IS NOT NULL
                  AND enabled = true
                  AND source_mutation_sequence <= ?
                  AND (last_seen_sync_run_id IS NULL OR last_seen_sync_run_id <> ?)
                ORDER BY external_id, id
                """,
                (rs, rowNum) -> new Candidate(
                        rs.getLong("id"),
                        rs.getString("external_id"),
                        documentKind(rs.getString("document_type")),
                        rs.getString("source_revision")),
                run.collectionId(),
                run.sourceNamespace(),
                run.snapshotStartSequence(),
                run.id());
        long protectedCount = queryLong("""
                SELECT COUNT(*) FROM rag_documents
                WHERE collection_id = ? AND source_namespace = ?
                  AND external_id IS NOT NULL AND enabled = true
                  AND source_mutation_sequence > ?
                  AND (last_seen_sync_run_id IS NULL OR last_seen_sync_run_id <> ?)
                """,
                run.collectionId(),
                run.sourceNamespace(),
                run.snapshotStartSequence(),
                run.id());
        long unresolvedLegacyCount = queryLong("""
                SELECT COUNT(*) FROM rag_documents
                WHERE collection_id = ? AND source_namespace = ?
                  AND external_id IS NOT NULL AND source_revision IS NULL
                  AND enabled = true AND source_mutation_sequence <= ?
                  AND (last_seen_sync_run_id IS NULL OR last_seen_sync_run_id <> ?)
                """,
                run.collectionId(),
                run.sourceNamespace(),
                run.snapshotStartSequence(),
                run.id());
        int textCount = (int) candidates.stream()
                .filter(candidate -> candidate.documentKind()
                        == DocumentSyncDocumentKind.TEXT)
                .count();
        int jsonCount = candidates.size() - textCount;
        return new CandidateSet(
                candidates,
                textCount,
                jsonCount,
                Math.toIntExact(protectedCount),
                Math.toIntExact(unresolvedLegacyCount),
                fingerprint(candidates));
    }

    private long countActiveExternal(RunRow run) {
        return queryLong("""
                SELECT COUNT(*) FROM rag_documents
                WHERE collection_id = ? AND source_namespace = ?
                  AND external_id IS NOT NULL AND enabled = true
                """,
                run.collectionId(),
                run.sourceNamespace());
    }

    private void incrementRunCount(
            RunRow run,
            DocumentSyncItemStatus itemStatus) {
        String column = switch (itemStatus) {
            case APPLIED -> "applied_count";
            case UNCHANGED -> "unchanged_count";
            case SKIPPED_NEWER_MUTATION -> "skipped_count";
            case FAILED -> "failed_count";
        };
        int updated = jdbcTemplate.update(
                "UPDATE rag_document_sync_runs SET " + column
                        + " = " + column
                        + " + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND lease_token_hash = ? "
                        + "AND status = 'ACTIVE' "
                        + "AND lease_expires_at >= CURRENT_TIMESTAMP",
                run.id(),
                run.leaseTokenHash());
        if (updated != 1) {
            throw invalidState("Sync run lease was lost while applying an item");
        }
    }

    private boolean hasFailedItems(UUID runId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_document_sync_run_items
                WHERE run_id = ? AND status = 'FAILED'
                """,
                Long.class,
                runId);
        return count != null && count > 0;
    }

    private LedgerRow findItem(UUID runId, String externalId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT external_id, document_kind, item_fingerprint,
                           source_revision, document_id, status,
                           error_code, error_message
                    FROM rag_document_sync_run_items
                    WHERE run_id = ? AND external_id = ?
                    """,
                    (rs, rowNum) -> new LedgerRow(
                            rs.getString("external_id"),
                            DocumentSyncDocumentKind.valueOf(
                                    rs.getString("document_kind")),
                            rs.getString("item_fingerprint"),
                            rs.getString("source_revision"),
                            (Long) rs.getObject("document_id"),
                            DocumentSyncItemStatus.valueOf(
                                    rs.getString("status")),
                            rs.getString("error_code"),
                            rs.getString("error_message")),
                    runId,
                    externalId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private RunRow findByClientRun(
            long collectionId,
            String namespace,
            String clientRunId) {
        try {
            return jdbcTemplate.queryForObject(
                    runSelect()
                            + " WHERE collection_id = ? AND source_namespace = ?"
                            + " AND client_run_id = ?",
                    this::mapRun,
                    collectionId,
                    namespace,
                    clientRunId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private RunRow findActive(long collectionId, String namespace) {
        try {
            return jdbcTemplate.queryForObject(
                    runSelect()
                            + " WHERE collection_id = ? AND source_namespace = ?"
                            + " AND status = 'ACTIVE'"
                            + " ORDER BY created_at DESC LIMIT 1",
                    this::mapRun,
                    collectionId,
                    namespace);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private RunRow requireRun(UUID runId) {
        try {
            return jdbcTemplate.queryForObject(
                    runSelect() + " WHERE id = ?",
                    this::mapRun,
                    runId);
        } catch (EmptyResultDataAccessException e) {
            throw new RagException(
                    ErrorCode.NOT_FOUND,
                    "Sync run not found");
        }
    }

    private RunRow requireRun(
            UUID runId,
            long collectionId,
            String namespace) {
        RunRow run = requireRun(runId);
        if (run.collectionId() != collectionId
                || !Objects.equals(run.sourceNamespace(), namespace)) {
            throw new RagException(ErrorCode.NOT_FOUND, "Sync run not found");
        }
        return run;
    }

    private String runSelect() {
        return """
                SELECT id, collection_id, source_namespace, client_run_id,
                       lease_token_hash, sync_generation,
                       snapshot_start_sequence, complete_sequence,
                       snapshot_mode, missing_policy, status,
                       lease_expires_at, preview_token_hash,
                       preview_fingerprint, preview_missing_count,
                       applied_count, unchanged_count, skipped_count,
                       failed_count, tombstoned_count
                FROM rag_document_sync_runs
                """;
    }

    private RunRow mapRun(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new RunRow(
                rs.getObject("id", UUID.class),
                rs.getLong("collection_id"),
                rs.getString("source_namespace"),
                rs.getString("client_run_id"),
                rs.getString("lease_token_hash"),
                rs.getLong("sync_generation"),
                rs.getLong("snapshot_start_sequence"),
                (Long) rs.getObject("complete_sequence"),
                DocumentSyncSnapshotMode.valueOf(rs.getString("snapshot_mode")),
                DocumentSyncMissingPolicy.valueOf(rs.getString("missing_policy")),
                DocumentSyncRunStatus.valueOf(rs.getString("status")),
                readOffsetDateTime(rs.getObject("lease_expires_at")),
                rs.getString("preview_token_hash"),
                rs.getString("preview_fingerprint"),
                (Integer) rs.getObject("preview_missing_count"),
                rs.getInt("applied_count"),
                rs.getInt("unchanged_count"),
                rs.getInt("skipped_count"),
                rs.getInt("failed_count"),
                rs.getInt("tombstoned_count"));
    }

    private DocumentSyncRunResponse toResponse(
            RunRow run,
            String collectionKey) {
        String statusPath = UriComponentsBuilder
                .fromPath("/api/v1/rag/document-sync-runs/" + run.id())
                .queryParam("collectionKey", collectionKey)
                .queryParam("sourceNamespace", run.sourceNamespace())
                .build()
                .encode()
                .toUriString();
        return new DocumentSyncRunResponse(
                run.id(),
                collectionKey,
                run.sourceNamespace(),
                run.clientRunId(),
                run.snapshotMode(),
                run.missingPolicy(),
                run.status(),
                run.syncGeneration(),
                run.snapshotStartSequence(),
                run.leaseExpiresAt(),
                run.appliedCount(),
                run.unchangedCount(),
                run.skippedCount(),
                run.failedCount(),
                run.tombstonedCount(),
                statusPath);
    }

    private DocumentSyncRunItemResponse toItemResponse(LedgerRow item) {
        return new DocumentSyncRunItemResponse(
                item.externalId(),
                item.documentKind(),
                item.status(),
                item.documentId(),
                item.sourceRevision(),
                item.errorCode(),
                item.errorMessage(),
                "NONE",
                null);
    }

    private DocumentSyncRunItemResponse failedItem(
            DocumentSyncRunItemRequest item,
            RuntimeException error) {
        return new DocumentSyncRunItemResponse(
                item.externalId(),
                item.documentKind(),
                DocumentSyncItemStatus.FAILED,
                null,
                item.sourceRevision(),
                errorCode(error),
                truncate(error.getMessage()),
                "NONE",
                null);
    }

    private int count(
            List<DocumentSyncRunItemResponse> items,
            DocumentSyncItemStatus status) {
        return (int) items.stream().filter(item -> item.status() == status).count();
    }

    private boolean sameBeginRequest(
            RunRow existing,
            DocumentSyncRunBeginRequest request,
            String namespace) {
        return Objects.equals(existing.sourceNamespace(), namespace)
                && Objects.equals(existing.clientRunId(), request.clientRunId().trim())
                && existing.snapshotMode() == request.snapshotMode()
                && existing.missingPolicy() == request.missingPolicy();
    }

    private RagCollection requireWritableCollection(String collectionKey) {
        return ApiKeyCollectionAccess.requireActiveCollectionByKey(
                collectionKey,
                ApiKeyCollectionAccess.currentPolicy(),
                collectionIdentityResolver);
    }

    private RagCollection requireReadableCollection(String collectionKey) {
        return requireWritableCollection(requireVisible(
                collectionKey, "collectionKey", 128));
    }

    private void requireCollectionAccess(long collectionId) {
        ApiKeyCollectionAccess.requireCollectionId(
                collectionId, ApiKeyCollectionAccess.currentPolicy());
    }

    private String collectionKey(long collectionId) {
        return collectionIdentityResolver.mapKeys(List.of(collectionId))
                .get(collectionId);
    }

    private void validateMode(
            DocumentSyncSnapshotMode snapshotMode,
            DocumentSyncMissingPolicy missingPolicy,
            boolean confirmExclusiveOffline) {
        if (snapshotMode == null || missingPolicy == null) {
            throw new IllegalArgumentException(
                    "snapshotMode and missingPolicy are required");
        }
        if (snapshotMode == DocumentSyncSnapshotMode.OFFLINE_MANIFEST
                && missingPolicy == DocumentSyncMissingPolicy.TOMBSTONE) {
            throw new IllegalArgumentException(
                    "OFFLINE_MANIFEST only supports missingPolicy=NONE");
        }
        boolean exclusiveTombstone = snapshotMode
                == DocumentSyncSnapshotMode.EXCLUSIVE_OFFLINE
                && missingPolicy == DocumentSyncMissingPolicy.TOMBSTONE;
        if (exclusiveTombstone != confirmExclusiveOffline) {
            throw new IllegalArgumentException(
                    "confirmExclusiveOffline must be true only for "
                            + "EXCLUSIVE_OFFLINE + TOMBSTONE");
        }
    }

    private void requireEnabled() {
        if (!properties.isSyncRunsEnabled()) {
            throw new RagException(
                    ErrorCode.SYNC_RUNS_DISABLED,
                    "Authoritative document sync runs are disabled");
        }
    }

    private static String requireLeaseToken(String value) {
        return requireVisible(value, "X-RAG-Sync-Lease", 512);
    }

    private static String requireVisible(
            String value,
            String field,
            int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current < 0x20 || current > 0x7e) {
                throw new IllegalArgumentException(
                        field + " must contain visible ASCII only");
            }
        }
        return normalized;
    }

    private String normalizeNamespace(String value) {
        String normalized = value == null || value.isBlank()
                ? DEFAULT_NAMESPACE
                : requireVisible(value, "sourceNamespace", 128);
        if (!properties.isAllowNonDefaultNamespace()
                && !DEFAULT_NAMESPACE.equals(normalized)) {
            throw new IllegalArgumentException(
                    "Non-default sourceNamespace is disabled");
        }
        return normalized;
    }

    private static String hashToken(String token) {
        return DigestUtils.sha256(token);
    }

    private String fingerprint(DocumentSyncRunItemRequest item) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("documentKind", item.documentKind().name());
        canonical.put("externalId", item.externalId().trim());
        canonical.put("sourceRevision", item.sourceRevision().trim());
        canonical.put("title", item.title());
        canonical.put("content", item.content());
        canonical.put("retrievalText", item.retrievalText());
        canonical.put("jsonbPayload", item.jsonbPayload());
        canonical.put("source", item.source());
        canonical.put("documentType", item.documentType());
        canonical.put("metadata", item.metadata());
        canonical.put("embeddingPolicy", item.effectiveEmbeddingPolicy().name());
        try {
            return DigestUtils.sha256(objectMapper.writeValueAsString(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot fingerprint sync-run item", e);
        }
    }

    private String fingerprint(List<Candidate> candidates) {
        StringBuilder value = new StringBuilder();
        for (Candidate candidate : candidates) {
            value.append(candidate.externalId()).append('\u0000')
                    .append(candidate.documentKind().name()).append('\u0000')
                    .append(candidate.sourceRevision()).append('\n');
        }
        return DigestUtils.sha256(value.toString());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH
                ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String errorCode(Throwable error) {
        if (error instanceof RagException ragException) {
            return ragException.getErrorCode();
        }
        return ErrorCode.BAD_REQUEST.getCode();
    }

    private static boolean isRunControlError(ErrorCode code) {
        return code == ErrorCode.SYNC_RUN_LEASE_CONFLICT
                || code == ErrorCode.ACTIVE_SYNC_RUN_EXISTS
                || code == ErrorCode.SYNC_RUN_INVALID_STATE
                || code == ErrorCode.SYNC_RUN_PREVIEW_CONFLICT
                || code == ErrorCode.SYNC_RUN_DELETE_PROTECTION
                || code == ErrorCode.SYNC_RUN_ITEM_CONFLICT;
    }

    private long queryLong(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0L : result;
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
                "Unsupported sync-run timestamp type: "
                        + (value == null ? "null" : value.getClass()));
    }

    private static RagException invalidState(String message) {
        return new RagException(ErrorCode.SYNC_RUN_INVALID_STATE, message);
    }

    private static <T> T requireResult(T value) {
        return Objects.requireNonNull(
                value, "Sync run transaction returned no result");
    }

    private static DocumentSyncDocumentKind documentKind(String documentType) {
        return "json-record".equals(documentType)
                ? DocumentSyncDocumentKind.JSON_RECORD
                : DocumentSyncDocumentKind.TEXT;
    }

    private record Candidate(
            long documentId,
            String externalId,
            DocumentSyncDocumentKind documentKind,
            String sourceRevision) {
    }

    private record CandidateSet(
            List<Candidate> candidates,
            int textCount,
            int jsonCount,
            int protectedCount,
            int unresolvedLegacyCount,
            String fingerprint) {
    }

    private record LedgerRow(
            String externalId,
            DocumentSyncDocumentKind documentKind,
            String itemFingerprint,
            String sourceRevision,
            Long documentId,
            DocumentSyncItemStatus status,
            String errorCode,
            String errorMessage) {
    }

    private record RunRow(
            UUID id,
            long collectionId,
            String sourceNamespace,
            String clientRunId,
            String leaseTokenHash,
            long syncGeneration,
            long snapshotStartSequence,
            Long completeSequence,
            DocumentSyncSnapshotMode snapshotMode,
            DocumentSyncMissingPolicy missingPolicy,
            DocumentSyncRunStatus status,
            OffsetDateTime leaseExpiresAt,
            String previewTokenHash,
            String previewFingerprint,
            Integer previewMissingCount,
            int appliedCount,
            int unchangedCount,
            int skippedCount,
            int failedCount,
            int tombstonedCount) {
    }
}
