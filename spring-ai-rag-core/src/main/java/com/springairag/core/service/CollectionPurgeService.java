package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgePreviewResponse;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagCollectionPurgeProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.observability.IntegrationObservationContext;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.util.DigestUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * preview-first、owner-scoped、单事务的 Collection 永久清理协调器。
 */
@Service
public class CollectionPurgeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RETIRED_NAME = "Retired collection";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RagCollectionRepository collectionRepository;
    private final CollectionPurgeAuthorization authorization;
    private final RagCollectionPurgeProperties properties;
    private final TransactionTemplate transactionTemplate;

    public CollectionPurgeService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagCollectionRepository collectionRepository,
            CollectionPurgeAuthorization authorization,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.collectionRepository = collectionRepository;
        this.authorization = authorization;
        this.properties = ragProperties.getCollectionPurge();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CollectionPurgePreviewResponse preview(
            String collectionKey,
            HttpServletRequest request) {
        authorization.requireAllowed(request);
        requireCollectionKey(collectionKey);
        cleanup();
        String owner = ChatPrincipal.from(request).id();
        long active = count("""
                SELECT COUNT(*) FROM rag_collection_purge_preview
                WHERE owner_principal_id = ?
                  AND status IN ('PREVIEWED', 'APPLYING')
                  AND operation_deadline > clock_timestamp()
                """, owner);
        if (active >= properties.getMaxActivePreviewsPerOwner()) {
            throw conflict("Too many active Collection purge previews");
        }

        RagCollection collection = collectionRepository.findByCollectionKey(collectionKey)
                .orElseThrow(() -> new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND,
                        "Collection not found"));
        IntegrationObservationContext.addAuthorizedCollection(
                request, collection.getId());
        if (collection.getPurgedAt() != null) {
            throw retired();
        }
        Plan plan = buildPlan(collection);
        validatePreviewable(plan);

        UUID id = UUID.randomUUID();
        String token = token();
        String fingerprint = fingerprint(collection, plan);
        Instant now = Instant.now();
        Instant previewDeadline = now.plus(properties.getConfirmationWindow());
        Instant operationDeadline = now.plus(properties.getOperationWindow());
        Instant resultExpiresAt = now.plus(properties.getResultRetention());
        Counts c = plan.counts();
        jdbcTemplate.update("""
                INSERT INTO rag_collection_purge_preview (
                    id, owner_principal_id, collection_id, collection_key,
                    collection_version, chat_commit_fence_version,
                    confirmation_token_hash, fingerprint, status,
                    document_count, external_document_count, local_document_count,
                    embedding_count, embedding_job_count, version_count,
                    keyword_chunk_count, repair_preview_count, repair_item_count,
                    derived_row_count, document_idempotency_operation_count,
                    feedback_count, feedback_document_reference_count,
                    document_audit_count, collection_audit_count,
                    relocation_marker_count, affected_chat_session_count,
                    chat_history_count, chat_memory_count, chat_summary_count,
                    chat_turn_operation_count, active_sync_run_count,
                    active_derivation_repair_count, active_chat_session_count,
                    unindexed_chat_reference_count,
                    unindexed_feedback_reference_count,
                    preview_deadline, operation_deadline, result_expires_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, 'PREVIEWED',
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                id, owner, collection.getId(), collection.getCollectionKey(),
                version(collection), chatFence(collection),
                DigestUtils.sha256(token), fingerprint,
                c.documentCount(), c.externalDocumentCount(), c.localDocumentCount(),
                c.embeddingCount(), c.embeddingJobCount(), c.versionCount(),
                c.keywordChunkCount(), c.repairPreviewCount(), c.repairItemCount(),
                c.derivedRowCount(), c.documentIdempotencyOperationCount(),
                c.feedbackCount(), c.feedbackDocumentReferenceCount(),
                c.documentAuditCount(), c.collectionAuditCount(),
                c.relocationMarkerCount(), c.affectedChatSessionCount(),
                c.chatHistoryCount(), c.chatMemoryCount(), c.chatSummaryCount(),
                c.chatTurnOperationCount(), c.activeSyncRunCount(),
                c.activeDerivationRepairCount(), c.activeChatSessionCount(),
                c.unindexedChatReferenceCount(),
                c.unindexedFeedbackReferenceCount(),
                Timestamp.from(previewDeadline), Timestamp.from(operationDeadline),
                Timestamp.from(resultExpiresAt));
        return response(id, collection, plan, token, fingerprint,
                previewDeadline, operationDeadline);
    }

    public CollectionPurgeResultResponse apply(
            CollectionPurgeApplyRequest requestBody,
            HttpServletRequest request) {
        authorization.requireAllowed(request);
        Objects.requireNonNull(requestBody, "request must not be null");
        requireCollectionKey(requestBody.collectionKey());
        String owner = ChatPrincipal.from(request).id();
        CollectionPurgeResultResponse result = transactionTemplate.execute(status ->
                applyTransaction(requestBody, owner, request));
        if (result == null) {
            throw conflict("Collection purge did not complete");
        }
        return result;
    }

    private CollectionPurgeResultResponse applyTransaction(
            CollectionPurgeApplyRequest requestBody,
            String owner,
            HttpServletRequest request) {
        Preview preview = loadPreview(requestBody.previewId(), owner);
        IntegrationObservationContext.addAuthorizedCollection(
                request, preview.collectionId());
        validateFrozenRequest(preview, requestBody);
        if ("COMPLETED".equals(preview.status())) {
            return readResult(preview.resultPayload());
        }
        requirePreviewApplicable(preview);
        String lease = claimApplyLease(preview, owner);
        fenceCollection(preview);
        Plan current = requireUnchangedPlan(preview);
        int deletedDocuments = deletePurgeTargets(preview, current);
        return retireCollection(preview, owner, lease, current, deletedDocuments);
    }

    private void requirePreviewApplicable(Preview preview) {
        Instant now = Instant.now();
        if (!"PREVIEWED".equals(preview.status())
                || now.isAfter(preview.previewDeadline())
                || now.isAfter(preview.operationDeadline())) {
            throw expired();
        }
    }

    private String claimApplyLease(Preview preview, String owner) {
        String lease = DigestUtils.sha256(UUID.randomUUID().toString());
        int claimed = jdbcTemplate.update("""
                UPDATE rag_collection_purge_preview
                SET status = 'APPLYING',
                    apply_lease_owner_hash = ?,
                    apply_lease_expires_at =
                        clock_timestamp() + (? * interval '1 millisecond')
                WHERE id = ? AND owner_principal_id = ?
                  AND status = 'PREVIEWED'
                  AND preview_deadline >= clock_timestamp()
                  AND operation_deadline >= clock_timestamp()
                """,
                lease, properties.getApplyLease().toMillis(),
                preview.id(), owner);
        if (claimed != 1) {
            throw conflict("Collection purge preview is already being applied");
        }
        return lease;
    }

    private void fenceCollection(Preview preview) {
        int fenced = jdbcTemplate.update("""
                UPDATE rag_collection
                SET deleted = TRUE,
                    enabled = FALSE,
                    deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                    version = version + 1
                WHERE id = ? AND purged_at IS NULL
                  AND version = ?
                  AND chat_commit_fence_version = ?
                """,
                preview.collectionId(), preview.collectionVersion(),
                preview.chatFenceVersion());
        if (fenced != 1) {
            throw conflict("Collection changed after purge preview");
        }
    }

    private Plan requireUnchangedPlan(Preview preview) {
        RagCollection collection = collectionRepository.findById(preview.collectionId())
                .orElseThrow(() -> conflict("Collection disappeared during purge"));
        Plan current = buildPlan(
                collection,
                preview.collectionVersion(),
                preview.chatFenceVersion());
        String currentFingerprint = fingerprint(
                collection, current,
                preview.collectionVersion(), preview.chatFenceVersion());
        if (!constantEquals(preview.fingerprint(), currentFingerprint)) {
            throw conflict("Collection purge plan changed; create a new preview");
        }
        validatePreviewable(current);
        return current;
    }

    private int deletePurgeTargets(Preview preview, Plan current) {
        deleteSessions(current.sessions());
        deleteByIds("rag_user_feedback", "id", current.feedbackIds());
        deleteByIds("rag_audit_log", "id", current.auditIds());
        deleteByIds("rag_derivation_repair_previews", "id", current.repairIds());
        deleteByIds("rag_document_idempotency_operations", "id",
                current.idempotencyIds());
        if (!current.documentIds().isEmpty()) {
            jdbcTemplate.update("DELETE FROM rag_embeddings WHERE document_id IN ("
                    + placeholders(current.documentIds().size()) + ")",
                    current.documentIds().toArray());
        }
        int deletedDocuments = jdbcTemplate.update(
                "DELETE FROM rag_documents WHERE collection_id = ?",
                preview.collectionId());
        if (deletedDocuments != current.counts().documentCount()) {
            throw conflict("Collection documents changed during purge");
        }
        return deletedDocuments;
    }

    private CollectionPurgeResultResponse retireCollection(
            Preview preview,
            String owner,
            String lease,
            Plan current,
            int deletedDocuments) {
        markCollectionRetired(preview);
        CollectionPurgeResultResponse result = buildRetiredResult(preview, current);
        completePurgePreview(preview, owner, lease, json(result));
        writePurgeAuditLog(preview, deletedDocuments);
        return result;
    }

    private void markCollectionRetired(Preview preview) {
        int retired = jdbcTemplate.update("""
                UPDATE rag_collection
                SET purged_at = CURRENT_TIMESTAMP,
                    version = version + 1,
                    name = ?,
                    description = NULL,
                    metadata = NULL
                WHERE id = ? AND purged_at IS NULL
                  AND version = ?
                """,
                RETIRED_NAME, preview.collectionId(),
                preview.collectionVersion() + 1);
        if (retired != 1) {
            throw conflict("Collection retirement fence was lost");
        }
    }

    private CollectionPurgeResultResponse buildRetiredResult(
            Preview preview, Plan current) {
        CollectionState finalState = jdbcTemplate.queryForObject("""
                SELECT deleted_at, purged_at, version
                FROM rag_collection WHERE id = ?
                """,
                (rs, row) -> new CollectionState(
                        rs.getTimestamp("deleted_at").toLocalDateTime(),
                        rs.getTimestamp("purged_at").toLocalDateTime(),
                        rs.getLong("version")),
                preview.collectionId());
        return new CollectionPurgeResultResponse(
                preview.id(), "RETIRED", preview.collectionId(),
                preview.collectionKey(), current.counts().documentCount(),
                current.counts().externalDocumentCount(),
                current.counts().localDocumentCount(),
                finalState.deletedAt(), finalState.purgedAt(),
                finalState.version());
    }

    private void completePurgePreview(
            Preview preview,
            String owner,
            String lease,
            String payload) {
        jdbcTemplate.update("""
                UPDATE rag_collection_purge_preview
                SET status = 'COMPLETED',
                    result_payload = ?::jsonb,
                    completed_at = clock_timestamp(),
                    apply_lease_owner_hash = NULL,
                    apply_lease_expires_at = NULL
                WHERE id = ? AND owner_principal_id = ?
                  AND status = 'APPLYING'
                  AND apply_lease_owner_hash = ?
                """,
                payload, preview.id(), owner, lease);
    }

    private void writePurgeAuditLog(Preview preview, int deletedDocuments) {
        jdbcTemplate.update("""
                INSERT INTO rag_audit_log(
                    operation, entity_type, entity_id, description, details)
                VALUES ('DELETE', 'Collection', ?, 'Collection permanently retired',
                        jsonb_build_object(
                            'operation', 'COLLECTION_PURGE',
                            'collectionId', ?,
                            'purgedDocumentCount', ?))
                """,
                Long.toString(preview.collectionId()),
                preview.collectionId(), deletedDocuments);
    }

    private void validateFrozenRequest(
            Preview preview, CollectionPurgeApplyRequest request) {
        if (!preview.collectionKey().equals(request.collectionKey())
                || !Objects.equals(
                preview.collectionVersion(),
                request.expectedCollectionVersion())
                || !Objects.equals(
                preview.chatFenceVersion(),
                request.expectedChatCommitFenceVersion())) {
            throw conflict("Collection purge preview does not match the request");
        }
        String tokenHash = request.confirmationToken() == null
                ? null
                : DigestUtils.sha256(request.confirmationToken());
        if (!constantEquals(preview.confirmationTokenHash(), tokenHash)
                || !constantEquals(preview.fingerprint(), request.fingerprint())) {
            throw new RagException(
                    ErrorCode.COLLECTION_PURGE_CONFIRMATION_INVALID,
                    "Collection purge confirmation is invalid");
        }
    }

    private Plan buildPlan(RagCollection collection) {
        return buildPlan(collection, version(collection), chatFence(collection));
    }

    private Plan buildPlan(
            RagCollection collection,
            long frozenCollectionVersion,
            long frozenChatFenceVersion) {
        long collectionId = collection.getId();
        List<Long> documents = longIds(
                "SELECT id FROM rag_documents WHERE collection_id = ? ORDER BY id",
                collectionId);
        String documentPredicate = inPredicate("document_id", documents);
        List<SessionRef> sessions = documents.isEmpty()
                ? List.of()
                : jdbcTemplate.query("""
                        SELECT DISTINCT history.owner_principal_id, history.session_id
                        FROM rag_chat_history history
                        JOIN rag_chat_history_source_document ref
                          ON ref.history_id = history.id
                        WHERE ref.document_id IN (""" + placeholders(documents.size()) + """
                        )
                        ORDER BY history.owner_principal_id NULLS FIRST, history.session_id
                        """,
                        (rs, row) -> new SessionRef(
                                rs.getString("owner_principal_id"),
                                rs.getString("session_id")),
                        documents.toArray());
        List<Long> feedbackIds = documents.isEmpty() ? List.of() : longIds(
                "SELECT DISTINCT feedback_id FROM rag_user_feedback_document WHERE "
                        + documentPredicate + " ORDER BY feedback_id",
                documents.toArray());
        List<Long> documentAuditIds = documents.isEmpty() ? List.of() : longIds(
                "SELECT id FROM rag_audit_log WHERE entity_type = 'Document' "
                        + "AND entity_id IN (" + placeholders(documents.size())
                        + ") ORDER BY id",
                documents.stream().map(String::valueOf).toArray());
        List<Long> collectionAuditIds = longIds("""
                SELECT id FROM rag_audit_log
                WHERE entity_type = 'Collection' AND entity_id = ?
                ORDER BY id
                """, Long.toString(collectionId));
        List<Long> auditIds = union(documentAuditIds, collectionAuditIds);
        List<UUID> repairIds = documents.isEmpty()
                ? uuidIds("""
                        SELECT id FROM rag_derivation_repair_previews
                        WHERE collection_id = ? ORDER BY id
                        """, collectionId)
                : uuidIds("""
                        SELECT DISTINCT preview.id
                        FROM rag_derivation_repair_previews preview
                        LEFT JOIN rag_derivation_repair_items item
                          ON item.repair_id = preview.id
                        WHERE preview.collection_id = ?
                           OR item.document_id IN (""" + placeholders(documents.size()) + """
                        )
                        ORDER BY preview.id
                        """, concat(collectionId, documents));
        List<Long> idempotencyIds = documents.isEmpty()
                ? longIds("""
                        SELECT id FROM rag_document_idempotency_operations
                        WHERE ? = ANY(authorization_collection_ids)
                        ORDER BY id
                        """, collectionId)
                : longIds("""
                        SELECT id FROM rag_document_idempotency_operations
                        WHERE result_document_id IN (""" + placeholders(documents.size()) + """
                           ) OR ? = ANY(authorization_collection_ids)
                        ORDER BY id
                        """, concat(documents, collectionId));
        Counts counts = counts(
                collectionId,
                documents,
                sessions,
                feedbackIds,
                repairIds,
                idempotencyIds,
                documentAuditIds,
                collectionAuditIds,
                frozenCollectionVersion,
                frozenChatFenceVersion);
        return new Plan(documents, sessions, feedbackIds, auditIds,
                repairIds, idempotencyIds, counts);
    }

    private Counts counts(
            long collectionId,
            List<Long> documents,
            List<SessionRef> sessions,
            List<Long> feedbackIds,
            List<UUID> repairIds,
            List<Long> idempotencyIds,
            List<Long> documentAuditIds,
            List<Long> collectionAuditIds,
            long frozenCollectionVersion,
            long frozenChatFenceVersion) {
        long documentCount = documents.size();
        long external = count("""
                SELECT COUNT(*) FROM rag_documents
                WHERE collection_id = ? AND external_id IS NOT NULL
                """, collectionId);
        long embeddings = countJoin("rag_embeddings", documents);
        long jobs = countJoin("rag_embedding_jobs", documents);
        long versions = countJoin("rag_document_versions", documents);
        long chunks = countJoin("rag_document_chunks", documents);
        long embeddingStates = countJoin("rag_document_embedding_state", documents);
        long localStates = countJoin("rag_document_local_index_state", documents);
        long repairItems = repairIds.isEmpty() ? 0 : countUuidJoin(
                "rag_derivation_repair_items", "repair_id", repairIds);
        long feedbackRefs = feedbackIds.isEmpty() ? 0 : countLongJoin(
                "rag_user_feedback_document", "feedback_id", feedbackIds);
        long chatHistory = sessionCount("rag_chat_history", sessions);
        long chatSummary = sessionCount("rag_chat_memory_summary", sessions);
        long chatTurn = sessionCount("rag_chat_turn_operations", sessions);
        long chatMemory = memoryCount(sessions);
        long activeSync = activeSyncCount(collectionId, documents);
        long activeRepair = activeRepairCount(collectionId, documents);
        long activeChat = activeSessionCount(sessions);
        long relocation = documents.isEmpty() ? 0 : countLongJoin(
                "rag_document_relocated_addresses", "document_id", documents);
        long unindexedChat = count("""
                SELECT COUNT(*) FROM rag_chat_history
                WHERE content_reference_index_complete = FALSE
                """);
        long unindexedFeedback = count("""
                SELECT COUNT(*) FROM rag_user_feedback
                WHERE content_reference_index_complete = FALSE
                """);
        long derived = embeddings + jobs + versions + chunks + embeddingStates
                + localStates + repairIds.size() + repairItems
                + idempotencyIds.size() + feedbackIds.size() + feedbackRefs
                + documentAuditIds.size() + collectionAuditIds.size()
                + chatHistory + chatSummary + chatTurn
                + chatMemory;
        return new Counts(
                documentCount, external, documentCount - external,
                embeddings, jobs, versions, chunks, repairIds.size(),
                repairItems, derived, idempotencyIds.size(),
                feedbackIds.size(), feedbackRefs,
                documentAuditIds.size(), collectionAuditIds.size(),
                relocation, sessions.size(), chatHistory, chatMemory,
                chatSummary, chatTurn, activeSync, activeRepair, activeChat,
                unindexedChat, unindexedFeedback,
                frozenCollectionVersion, frozenChatFenceVersion);
    }

    private void validatePreviewable(Plan plan) {
        Counts c = plan.counts();
        if (c.unindexedChatReferenceCount() > 0
                || c.unindexedFeedbackReferenceCount() > 0) {
            throw conflict("Content reference indexes are incomplete");
        }
        if (c.activeSyncRunCount() > 0
                || c.activeDerivationRepairCount() > 0
                || c.activeChatSessionCount() > 0) {
            throw conflict("Collection has active work or Chat sessions");
        }
        if (c.documentCount() > properties.getMaxDocuments()
                || c.embeddingCount() > properties.getMaxEmbeddings()
                || c.versionCount() > properties.getMaxVersions()
                || c.derivedRowCount() > properties.getMaxDerivedRows()
                || c.affectedChatSessionCount()
                > properties.getMaxAffectedChatSessions()
                || c.chatRows() > properties.getMaxChatRows()) {
            throw conflict("Collection purge exceeds configured synchronous limits");
        }
    }

    private String fingerprint(RagCollection collection, Plan plan) {
        return fingerprint(collection, plan, version(collection), chatFence(collection));
    }

    private String fingerprint(
            RagCollection collection, Plan plan,
            long collectionVersion, long chatFenceVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("collectionId", collection.getId());
        body.put("collectionKey", collection.getCollectionKey());
        body.put("collectionVersion", collectionVersion);
        body.put("chatCommitFenceVersion", chatFenceVersion);
        body.put("counts", plan.counts());
        body.put("documentIds", plan.documentIds());
        body.put("sessions", plan.sessions());
        body.put("feedbackIds", plan.feedbackIds());
        body.put("auditIds", plan.auditIds());
        body.put("repairIds", plan.repairIds());
        body.put("idempotencyIds", plan.idempotencyIds());
        return DigestUtils.sha256(json(body));
    }

    @Scheduled(
            fixedDelayString = "${rag.collection-purge.cleanup-interval:PT1H}",
            initialDelayString = "${rag.collection-purge.cleanup-interval:PT1H}")
    public void scheduledCleanup() {
        cleanup();
    }

    void cleanup() {
        int limit = properties.getCleanupBatchSize();
        jdbcTemplate.update("""
                WITH candidate AS (
                    SELECT id FROM rag_collection_purge_preview
                    WHERE status = 'APPLYING'
                      AND operation_deadline > clock_timestamp()
                      AND apply_lease_expires_at < clock_timestamp()
                    ORDER BY apply_lease_expires_at
                    LIMIT ?
                )
                UPDATE rag_collection_purge_preview preview
                SET status = 'PREVIEWED',
                    apply_lease_owner_hash = NULL,
                    apply_lease_expires_at = NULL
                FROM candidate WHERE preview.id = candidate.id
                """, limit);
        jdbcTemplate.update("""
                WITH candidate AS (
                    SELECT id FROM rag_collection_purge_preview
                    WHERE status IN ('PREVIEWED', 'APPLYING')
                      AND operation_deadline <= clock_timestamp()
                    ORDER BY operation_deadline
                    LIMIT ?
                )
                UPDATE rag_collection_purge_preview preview
                SET status = 'EXPIRED', completed_at = clock_timestamp(),
                    apply_lease_owner_hash = NULL,
                    apply_lease_expires_at = NULL
                FROM candidate WHERE preview.id = candidate.id
                """, limit);
        jdbcTemplate.update("""
                DELETE FROM rag_collection_purge_preview
                WHERE id IN (
                    SELECT id FROM rag_collection_purge_preview
                    WHERE status IN ('COMPLETED', 'EXPIRED')
                      AND result_expires_at <= clock_timestamp()
                    ORDER BY result_expires_at
                    LIMIT ?
                )
                """, limit);
    }

    private void deleteSessions(List<SessionRef> sessions) {
        for (SessionRef session : sessions) {
            if (session.owner() == null) {
                jdbcTemplate.update(
                        "DELETE FROM rag_chat_history "
                                + "WHERE owner_principal_id IS NULL AND session_id = ?",
                        session.session());
                jdbcTemplate.update(
                        "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?",
                        session.session());
                continue;
            }
            jdbcTemplate.update(
                    "DELETE FROM rag_chat_history "
                            + "WHERE owner_principal_id = ? AND session_id = ?",
                    session.owner(), session.session());
            jdbcTemplate.update(
                    "DELETE FROM rag_chat_memory_summary "
                            + "WHERE owner_principal_id = ? AND session_id = ?",
                    session.owner(), session.session());
            jdbcTemplate.update(
                    "DELETE FROM rag_chat_turn_operations "
                            + "WHERE owner_principal_id = ? AND session_id = ?",
                    session.owner(), session.session());
            jdbcTemplate.update(
                    "DELETE FROM rag_chat_session_lease "
                            + "WHERE owner_principal_id = ? AND session_id = ? "
                            + "AND expires_at <= clock_timestamp()",
                    session.owner(), session.session());
            jdbcTemplate.update(
                    "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?",
                    memoryConversationId(session.owner(), session.session()));
        }
    }

    private Preview loadPreview(UUID id, String owner) {
        List<Preview> rows = jdbcTemplate.query("""
                SELECT id, owner_principal_id, collection_id, collection_key,
                       collection_version, chat_commit_fence_version,
                       confirmation_token_hash, fingerprint, status,
                       preview_deadline, operation_deadline, result_payload::text
                FROM rag_collection_purge_preview
                WHERE id = ? AND owner_principal_id = ?
                """,
                (rs, row) -> new Preview(
                        rs.getObject("id", UUID.class),
                        rs.getString("owner_principal_id"),
                        rs.getLong("collection_id"),
                        rs.getString("collection_key"),
                        rs.getLong("collection_version"),
                        rs.getLong("chat_commit_fence_version"),
                        rs.getString("confirmation_token_hash"),
                        rs.getString("fingerprint"),
                        rs.getString("status"),
                        rs.getTimestamp("preview_deadline").toInstant(),
                        rs.getTimestamp("operation_deadline").toInstant(),
                        rs.getString("result_payload")),
                id, owner);
        if (rows.isEmpty()) {
            throw expired();
        }
        return rows.getFirst();
    }

    private CollectionPurgePreviewResponse response(
            UUID id, RagCollection collection, Plan plan, String token,
            String fingerprint, Instant previewDeadline, Instant operationDeadline) {
        Counts c = plan.counts();
        return new CollectionPurgePreviewResponse(
                id, collection.getId(), collection.getCollectionKey(),
                version(collection), chatFence(collection), "PREVIEWED",
                c.documentCount(), c.externalDocumentCount(), c.localDocumentCount(),
                c.embeddingCount(), c.embeddingJobCount(), c.versionCount(),
                c.keywordChunkCount(), c.repairPreviewCount(), c.repairItemCount(),
                c.derivedRowCount(), c.documentIdempotencyOperationCount(),
                c.feedbackCount(), c.feedbackDocumentReferenceCount(),
                c.documentAuditCount(), c.collectionAuditCount(),
                c.relocationMarkerCount(), c.affectedChatSessionCount(),
                c.chatHistoryCount(), c.chatMemoryCount(), c.chatSummaryCount(),
                c.chatTurnOperationCount(), c.activeSyncRunCount(),
                c.activeDerivationRepairCount(), c.activeChatSessionCount(),
                c.unindexedChatReferenceCount(),
                c.unindexedFeedbackReferenceCount(), token, fingerprint,
                OffsetDateTime.ofInstant(previewDeadline, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(operationDeadline, ZoneOffset.UTC));
    }

    private long activeSyncCount(long collectionId, List<Long> documents) {
        if (documents.isEmpty()) {
            return count("""
                    SELECT COUNT(*) FROM rag_document_sync_runs
                    WHERE collection_id = ? AND status = 'ACTIVE'
                      AND lease_expires_at > clock_timestamp()
                    """, collectionId);
        }
        return count("""
                SELECT COUNT(DISTINCT run.id)
                FROM rag_document_sync_runs run
                LEFT JOIN rag_document_sync_run_items item ON item.run_id = run.id
                WHERE run.status = 'ACTIVE'
                  AND run.lease_expires_at > clock_timestamp()
                  AND (run.collection_id = ? OR item.document_id IN ("""
                + placeholders(documents.size()) + "))",
                concat(collectionId, documents));
    }

    private long activeRepairCount(long collectionId, List<Long> documents) {
        if (documents.isEmpty()) {
            return count("""
                    SELECT COUNT(*) FROM rag_derivation_repair_previews
                    WHERE collection_id = ? AND status = 'APPLYING'
                      AND operation_deadline > clock_timestamp()
                    """, collectionId);
        }
        return count("""
                SELECT COUNT(DISTINCT preview.id)
                FROM rag_derivation_repair_previews preview
                LEFT JOIN rag_derivation_repair_items item
                  ON item.repair_id = preview.id
                WHERE preview.status = 'APPLYING'
                  AND preview.operation_deadline > clock_timestamp()
                  AND (preview.collection_id = ? OR item.document_id IN ("""
                + placeholders(documents.size()) + "))",
                concat(collectionId, documents));
    }

    private long activeSessionCount(List<SessionRef> sessions) {
        long total = 0;
        for (SessionRef session : sessions) {
            if (session.owner() != null) {
                total += count("""
                        SELECT COUNT(*) FROM rag_chat_session_lease
                        WHERE owner_principal_id = ? AND session_id = ?
                          AND expires_at > clock_timestamp()
                        """, session.owner(), session.session());
            }
        }
        return total;
    }

    private long sessionCount(String table, List<SessionRef> sessions) {
        long total = 0;
        for (SessionRef session : sessions) {
            if (session.owner() == null) {
                total += count("SELECT COUNT(*) FROM " + table
                        + " WHERE owner_principal_id IS NULL AND session_id = ?",
                        session.session());
            } else {
                total += count("SELECT COUNT(*) FROM " + table
                        + " WHERE owner_principal_id = ? AND session_id = ?",
                        session.owner(), session.session());
            }
        }
        return total;
    }

    private long memoryCount(List<SessionRef> sessions) {
        long total = 0;
        for (SessionRef session : sessions) {
            String id = session.owner() == null
                    ? session.session()
                    : memoryConversationId(session.owner(), session.session());
            total += count("""
                    SELECT COUNT(*) FROM spring_ai_chat_memory
                    WHERE conversation_id = ?
                    """, id);
        }
        return total;
    }

    private long countJoin(String table, List<Long> documents) {
        return countLongJoin(table, "document_id", documents);
    }

    private long countLongJoin(String table, String column, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return count("SELECT COUNT(*) FROM " + table + " WHERE " + column
                + " IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    private long countUuidJoin(String table, String column, List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return count("SELECT COUNT(*) FROM " + table + " WHERE " + column
                + " IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    private void deleteByIds(String table, String column, List<?> ids) {
        if (!ids.isEmpty()) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE " + column
                    + " IN (" + placeholders(ids.size()) + ")", ids.toArray());
        }
    }

    private List<Long> longIds(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, row) -> rs.getLong(1), args);
    }

    private List<UUID> uuidIds(String sql, Object... args) {
        return jdbcTemplate.query(
                sql, (rs, row) -> rs.getObject(1, UUID.class), args);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize purge plan", e);
        }
    }

    private CollectionPurgeResultResponse readResult(String payload) {
        try {
            return objectMapper.readValue(
                    payload, CollectionPurgeResultResponse.class);
        } catch (JsonProcessingException e) {
            throw conflict("Stored Collection purge result is invalid");
        }
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantEquals(String left, String right) {
        return left != null && right != null
                && java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static long version(RagCollection collection) {
        return collection.getVersion() == null ? 0 : collection.getVersion();
    }

    private static long chatFence(RagCollection collection) {
        return collection.getChatCommitFenceVersion() == null
                ? 0 : collection.getChatCommitFenceVersion();
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String inPredicate(String column, List<Long> ids) {
        return column + " IN (" + placeholders(ids.size()) + ")";
    }

    private static Object[] concat(Object first, List<?> rest) {
        List<Object> values = new ArrayList<>();
        values.add(first);
        values.addAll(rest);
        return values.toArray();
    }

    private static Object[] concat(List<?> first, Object last) {
        List<Object> values = new ArrayList<>(first);
        values.add(last);
        return values.toArray();
    }

    private static List<Long> union(List<Long> first, List<Long> second) {
        LinkedHashSet<Long> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static String memoryConversationId(String owner, String session) {
        return new ChatPrincipal(owner, "PURGE", false)
                .memoryConversationId(session);
    }

    private static void requireCollectionKey(String key) {
        if (key == null || !com.springairag.api.validation.CollectionKeyValidator
                .isValid(key)) {
            throw new IllegalArgumentException(
                    "collectionKey must contain 1-128 visible ASCII characters");
        }
    }

    private static RagException conflict(String message) {
        return new RagException(ErrorCode.COLLECTION_PURGE_CONFLICT, message);
    }

    private static RagException expired() {
        return new RagException(
                ErrorCode.COLLECTION_PURGE_PREVIEW_EXPIRED,
                "Collection purge preview is expired or unavailable");
    }

    private static RagException retired() {
        return new RagException(
                ErrorCode.COLLECTION_ALREADY_RETIRED,
                "Collection is permanently retired");
    }

    private record Preview(
            UUID id,
            String owner,
            long collectionId,
            String collectionKey,
            long collectionVersion,
            long chatFenceVersion,
            String confirmationTokenHash,
            String fingerprint,
            String status,
            Instant previewDeadline,
            Instant operationDeadline,
            String resultPayload) {
    }

    private record SessionRef(String owner, String session) {
    }

    private record Plan(
            List<Long> documentIds,
            List<SessionRef> sessions,
            List<Long> feedbackIds,
            List<Long> auditIds,
            List<UUID> repairIds,
            List<Long> idempotencyIds,
            Counts counts) {
    }

    private record Counts(
            long documentCount,
            long externalDocumentCount,
            long localDocumentCount,
            long embeddingCount,
            long embeddingJobCount,
            long versionCount,
            long keywordChunkCount,
            long repairPreviewCount,
            long repairItemCount,
            long derivedRowCount,
            long documentIdempotencyOperationCount,
            long feedbackCount,
            long feedbackDocumentReferenceCount,
            long documentAuditCount,
            long collectionAuditCount,
            long relocationMarkerCount,
            long affectedChatSessionCount,
            long chatHistoryCount,
            long chatMemoryCount,
            long chatSummaryCount,
            long chatTurnOperationCount,
            long activeSyncRunCount,
            long activeDerivationRepairCount,
            long activeChatSessionCount,
            long unindexedChatReferenceCount,
            long unindexedFeedbackReferenceCount,
            long frozenCollectionVersion,
            long frozenChatFenceVersion) {

        long chatRows() {
            return chatHistoryCount + chatMemoryCount
                    + chatSummaryCount + chatTurnOperationCount;
        }
    }

    private record CollectionState(
            LocalDateTime deletedAt,
            LocalDateTime purgedAt,
            long version) {
    }
}
