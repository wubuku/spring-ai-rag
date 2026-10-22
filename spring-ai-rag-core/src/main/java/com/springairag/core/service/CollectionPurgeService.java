package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionPurgeApplyRequest;
import com.springairag.api.dto.CollectionPurgePreviewResponse;
import com.springairag.api.dto.CollectionPurgeResultResponse;
import com.springairag.api.dto.LongRunningOperationResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.IdempotencyKeyValidator;
import com.springairag.core.config.RagCollectionPurgeProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.exception.LongRunningOperationCapacityException;
import com.springairag.core.exception.CollectionPurgePreviewTimeoutException;
import com.springairag.core.observability.IntegrationObservationContext;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.util.DigestUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
    private static final int ASYNC_PREVIEW_GLOBAL_CAPACITY = 2;
    private static final int ASYNC_PREVIEW_FETCH_SIZE = 256;
    private static final int OPERATION_OWNER_CAPACITY = 4;
    private static final int OPERATION_GLOBAL_CAPACITY = 500;
    private static final List<String> ASYNC_MANIFEST_KINDS =
            List.of("D", "S", "F", "A", "R", "I");
    private static final List<String> ASYNC_DELETE_PHASES = List.of(
            "CHAT_REFERENCES", "CHAT_HISTORY", "CHAT_SUMMARIES", "CHAT_TURNS",
            "CHAT_LEASES", "CHAT_MEMORY", "FEEDBACK_REFERENCES", "FEEDBACK",
            "AUDIT", "REPAIR_ITEMS", "REPAIR_PREVIEWS", "IDEMPOTENCY",
            "EMBEDDINGS", "PROCESSING_STAGES", "OPERATION_RUN_REFS",
            "PROCESSING_RUNS", "EMBEDDING_JOBS", "DOCUMENT_VERSIONS",
            "DOCUMENT_CHUNKS", "LOCAL_INDEX_STATE", "EMBEDDING_STATE",
            "RELOCATED_ADDRESSES", "DOCUMENTS", "RETIRE");
    private static final String ASYNC_TARGET_CTES = """
            WITH docs AS (
                SELECT id, external_id FROM rag_documents WHERE collection_id = ?
            ), sessions AS (
                SELECT DISTINCT history.owner_principal_id, history.session_id
                FROM rag_chat_history history
                JOIN rag_chat_history_source_document ref ON ref.history_id = history.id
                JOIN docs ON docs.id = ref.document_id
            ), feedback_ids AS (
                SELECT DISTINCT feedback_id FROM rag_user_feedback_document
                WHERE document_id IN (SELECT id FROM docs)
            ), repair_ids AS (
                SELECT DISTINCT preview.id FROM rag_derivation_repair_previews preview
                LEFT JOIN rag_derivation_repair_items item ON item.repair_id = preview.id
                WHERE preview.collection_id = ?
                   OR item.document_id IN (SELECT id FROM docs)
            ), idempotency_ids AS (
                SELECT id FROM rag_document_idempotency_operations
                WHERE result_document_id IN (SELECT id FROM docs)
                   OR ? = ANY(authorization_collection_ids)
            ), document_audit_ids AS (
                SELECT id FROM rag_audit_log WHERE entity_type = 'Document'
                  AND entity_id IN (SELECT id::text FROM docs)
            ), collection_audit_ids AS (
                SELECT id FROM rag_audit_log WHERE entity_type = 'Collection'
                  AND entity_id = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RagCollectionRepository collectionRepository;
    private final CollectionPurgeAuthorization authorization;
    private final RagCollectionPurgeProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate asyncPreviewScanTransaction;
    private final LongRunningOperationService operationService;

    public CollectionPurgeService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagCollectionRepository collectionRepository,
            CollectionPurgeAuthorization authorization,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, objectMapper, collectionRepository, authorization,
                ragProperties, transactionManager, null);
    }

    @Autowired
    public CollectionPurgeService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagCollectionRepository collectionRepository,
            CollectionPurgeAuthorization authorization,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager,
            LongRunningOperationService operationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.collectionRepository = collectionRepository;
        this.authorization = authorization;
        this.properties = ragProperties.getCollectionPurge();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.asyncPreviewScanTransaction = new TransactionTemplate(transactionManager);
        this.operationService = operationService;
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

        return createPreview(collection, plan, owner, "SYNC");
    }

    /**
     * 为大集合创建异步清理预览。旧同步 preview 的边界和响应保持不变。
     */
    public CollectionPurgePreviewResponse previewAsync(
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
                        ErrorCode.COLLECTION_NOT_FOUND, "Collection not found"));
        IntegrationObservationContext.addAuthorizedCollection(request, collection.getId());
        if (collection.getPurgedAt() != null) {
            throw retired();
        }
        PreviewAdmissionLease lease = acquireAsyncPreviewAdmission(owner);
        Instant deadline = Instant.now().plus(properties.getAsyncPreviewDeadline());
        try {
            AsyncPreviewScan scan = asyncPreviewScanTransaction.execute(status ->
                    scanAsyncPreview(collection, lease, deadline));
            if (scan == null) {
                throw asyncPreviewTimeout();
            }
            requireAsyncPreviewLease(lease, deadline);
            return createPreview(
                    collection, scan.counts(), owner, "ASYNC", scan.fingerprint());
        } catch (DataAccessException exception) {
            if (isPostgresStatementTimeout(exception) || !Instant.now().isBefore(deadline)) {
                throw asyncPreviewTimeout();
            }
            throw exception;
        } finally {
            releaseAsyncPreviewAdmission(lease);
        }
    }

    private CollectionPurgePreviewResponse createPreview(
            RagCollection collection, Counts counts, String owner,
            String executionMode, String fingerprint) {
        return persistPreview(collection, counts, owner, executionMode, fingerprint);
    }

    private PreviewAdmissionLease acquireAsyncPreviewAdmission(String owner) {
        UUID token = UUID.randomUUID();
        String leaseOwner = "purge-preview:" + UUID.randomUUID();
        Boolean admitted = transactionTemplate.execute(status -> {
            int released = jdbcTemplate.update("""
                    WITH expired AS (
                        UPDATE rag_collection_purge_preview_admission_owner
                        SET lease_owner = NULL, lease_token = NULL,
                            lease_expires_at = NULL, updated_at = clock_timestamp()
                        WHERE lease_token IS NOT NULL
                          AND lease_expires_at <= clock_timestamp()
                        RETURNING owner_principal_id
                    )
                    UPDATE rag_collection_purge_preview_admission_global
                    SET active_count = GREATEST(
                            0, active_count - (SELECT COUNT(*) FROM expired)),
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                    """);
            if (released != 1) {
                throw new IllegalStateException(
                        "Collection purge preview admission row is missing");
            }
            int ownerClaimed = jdbcTemplate.update("""
                    INSERT INTO rag_collection_purge_preview_admission_owner (
                        owner_principal_id, lease_owner, lease_token, lease_expires_at)
                    VALUES (?, ?, ?, clock_timestamp() + (? * interval '1 millisecond'))
                    ON CONFLICT (owner_principal_id) DO UPDATE
                    SET lease_owner = EXCLUDED.lease_owner,
                        lease_token = EXCLUDED.lease_token,
                        lease_expires_at = EXCLUDED.lease_expires_at,
                        updated_at = clock_timestamp()
                    WHERE rag_collection_purge_preview_admission_owner.lease_token IS NULL
                    """,
                    owner, leaseOwner, token,
                    properties.getAsyncPreviewAdmissionLease().toMillis());
            if (ownerClaimed != 1) {
                return false;
            }
            int globalClaimed = jdbcTemplate.update("""
                    UPDATE rag_collection_purge_preview_admission_global
                    SET active_count = active_count + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1 AND active_count < ?
                    """, ASYNC_PREVIEW_GLOBAL_CAPACITY);
            if (globalClaimed != 1) {
                throw new LongRunningOperationCapacityException(
                        "Async Collection purge preview capacity is full");
            }
            return true;
        });
        if (!Boolean.TRUE.equals(admitted)) {
            throw new LongRunningOperationCapacityException(
                    "This owner already has an async Collection purge preview scan");
        }
        return new PreviewAdmissionLease(owner, leaseOwner, token, Instant.now());
    }

    private void releaseAsyncPreviewAdmission(PreviewAdmissionLease lease) {
        transactionTemplate.executeWithoutResult(status -> {
            int released = jdbcTemplate.update("""
                    UPDATE rag_collection_purge_preview_admission_owner
                    SET lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL, updated_at = clock_timestamp()
                    WHERE owner_principal_id = ? AND lease_owner = ? AND lease_token = ?
                    """, lease.owner(), lease.leaseOwner(), lease.token());
            if (released == 1) {
                jdbcTemplate.update("""
                        UPDATE rag_collection_purge_preview_admission_global
                        SET active_count = GREATEST(0, active_count - 1),
                            updated_at = clock_timestamp()
                        WHERE singleton_id = 1
                        """);
            }
        });
    }

    private void requireAsyncPreviewLease(
            PreviewAdmissionLease lease, Instant deadline) {
        checkAsyncPreviewDeadline(deadline);
        int renewed = jdbcTemplate.update("""
                UPDATE rag_collection_purge_preview_admission_owner
                SET lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE owner_principal_id = ? AND lease_owner = ? AND lease_token = ?
                  AND lease_expires_at > clock_timestamp()
                """,
                properties.getAsyncPreviewAdmissionLease().toMillis(),
                lease.owner(), lease.leaseOwner(), lease.token());
        if (renewed != 1) {
            throw asyncPreviewTimeout();
        }
    }

    private AsyncPreviewScan scanAsyncPreview(
            RagCollection collection, PreviewAdmissionLease lease, Instant deadline) {
        requireAsyncPreviewLease(lease, deadline);
        int statementTimeoutMillis = Math.toIntExact(
                properties.getAsyncPreviewStatementTimeout().toMillis());
        jdbcTemplate.queryForObject("SELECT set_config('statement_timeout', ?, true)",
                String.class, statementTimeoutMillis + "ms");
        requireAsyncCollectionSnapshot(collection, deadline, lease);
        Counts counts = readAsyncCounts(collection, deadline, lease);
        if (counts.documentCount() > properties.getAsyncPreviewMaxDocuments()
                || counts.derivedRowCount() > properties.getAsyncPreviewMaxDerivedRows()) {
            throw asyncPreviewLimitExceeded();
        }
        if (counts.unindexedChatReferenceCount() > 0
                || counts.unindexedFeedbackReferenceCount() > 0) {
            throw conflict("Content reference indexes are incomplete");
        }
        if (counts.activeSyncRunCount() > 0
                || counts.activeDerivationRepairCount() > 0
                || counts.activeChatSessionCount() > 0) {
            throw conflict("Collection has active work or Chat sessions");
        }
        if (hasActiveDocumentProcessing(collection.getId(), deadline, lease)) {
            throw conflict("Collection has active document processing work");
        }
        String targetDigest = streamAsyncFingerprint(collection, deadline, lease);
        requireAsyncPreviewLease(lease, deadline);
        Map<String, Object> fingerprintBody = new LinkedHashMap<>();
        fingerprintBody.put("version", 1);
        fingerprintBody.put("collectionId", collection.getId());
        fingerprintBody.put("collectionKey", collection.getCollectionKey());
        fingerprintBody.put("collectionVersion", version(collection));
        fingerprintBody.put("chatCommitFenceVersion", chatFence(collection));
        fingerprintBody.put("counts", counts);
        fingerprintBody.put("targetDigest", targetDigest);
        return new AsyncPreviewScan(counts, DigestUtils.sha256(json(fingerprintBody)));
    }

    private boolean hasActiveDocumentProcessing(
            long collectionId, Instant deadline, PreviewAdmissionLease lease) {
        Long active = jdbcTemplate.query(asyncStatement("""
                        WITH docs AS (
                            SELECT id FROM rag_documents WHERE collection_id = ?
                        )
                        SELECT
                          (SELECT COUNT(*) FROM rag_embedding_jobs job
                           JOIN docs ON docs.id = job.document_id
                           WHERE job.status IN ('QUEUED', 'RUNNING'))
                          +
                          (SELECT COUNT(*) FROM rag_document_processing_runs run
                           JOIN docs ON docs.id = run.document_id
                           WHERE run.status IN ('QUEUED', 'RUNNING'))
                        """, new Object[]{collectionId}, deadline, lease),
                (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : 0L);
        return active != null && active > 0;
    }

    private void requireAsyncCollectionSnapshot(
            RagCollection collection, Instant deadline, PreviewAdmissionLease lease) {
        Integer matches = jdbcTemplate.query(asyncStatement("""
                        SELECT COUNT(*) FROM rag_collection
                        WHERE id = ? AND version = ? AND chat_commit_fence_version = ?
                        """,
                        new Object[]{collection.getId(), version(collection),
                                chatFence(collection)}, deadline, lease),
                (ResultSetExtractor<Integer>) rs -> rs.next() ? rs.getInt(1) : 0);
        if (matches == null || matches != 1) {
            throw conflict("Collection changed before async purge preview scanning");
        }
    }

    private Counts readAsyncCounts(
            RagCollection collection, Instant deadline, PreviewAdmissionLease lease) {
        long collectionId = collection.getId();
        String sql = ASYNC_TARGET_CTES + """
                SELECT
                  (SELECT COUNT(*) FROM docs) AS document_count,
                  (SELECT COUNT(*) FROM docs WHERE external_id IS NOT NULL) AS external_count,
                  (SELECT COUNT(*) FROM rag_embeddings e JOIN docs d ON d.id = e.document_id) AS embedding_count,
                  (SELECT COUNT(*) FROM rag_embedding_jobs e JOIN docs d ON d.id = e.document_id) AS embedding_job_count,
                  (SELECT COUNT(*) FROM rag_document_versions e JOIN docs d ON d.id = e.document_id) AS version_count,
                  (SELECT COUNT(*) FROM rag_document_chunks e JOIN docs d ON d.id = e.document_id) AS keyword_chunk_count,
                  (SELECT COUNT(*) FROM repair_ids) AS repair_preview_count,
                  (SELECT COUNT(*) FROM rag_derivation_repair_items i JOIN repair_ids r ON r.id = i.repair_id) AS repair_item_count,
                  (SELECT COUNT(*) FROM idempotency_ids) AS idempotency_count,
                  (SELECT COUNT(*) FROM feedback_ids) AS feedback_count,
                  (SELECT COUNT(*) FROM rag_user_feedback_document r JOIN feedback_ids f ON f.feedback_id = r.feedback_id) AS feedback_reference_count,
                  (SELECT COUNT(*) FROM document_audit_ids) AS document_audit_count,
                  (SELECT COUNT(*) FROM collection_audit_ids) AS collection_audit_count,
                  (SELECT COUNT(*) FROM rag_document_relocated_addresses r JOIN docs d ON d.id = r.document_id) AS relocation_count,
                  (SELECT COUNT(*) FROM sessions) AS session_count,
                  (SELECT COUNT(*) FROM rag_chat_history h JOIN sessions s ON s.owner_principal_id IS NOT DISTINCT FROM h.owner_principal_id AND s.session_id = h.session_id) AS chat_history_count,
                  (SELECT COUNT(*) FROM rag_chat_memory_summary h JOIN sessions s ON s.owner_principal_id IS NOT DISTINCT FROM h.owner_principal_id AND s.session_id = h.session_id) AS chat_summary_count,
                  (SELECT COUNT(*) FROM rag_chat_turn_operations h JOIN sessions s ON s.owner_principal_id IS NOT DISTINCT FROM h.owner_principal_id AND s.session_id = h.session_id) AS chat_turn_count,
                  (SELECT COUNT(*) FROM rag_document_embedding_state e JOIN docs d ON d.id = e.document_id) AS embedding_state_count,
                  (SELECT COUNT(*) FROM rag_document_local_index_state e JOIN docs d ON d.id = e.document_id) AS local_state_count,
                  (SELECT COUNT(DISTINCT run.id) FROM rag_document_sync_runs run LEFT JOIN rag_document_sync_run_items item ON item.run_id = run.id WHERE run.status = 'ACTIVE' AND run.lease_expires_at > clock_timestamp() AND (run.collection_id = ? OR item.document_id IN (SELECT id FROM docs))) AS active_sync_count,
                  (SELECT COUNT(DISTINCT preview.id) FROM rag_derivation_repair_previews preview JOIN repair_ids r ON r.id = preview.id WHERE preview.status = 'APPLYING' AND preview.operation_deadline > clock_timestamp()) AS active_repair_count,
                  (SELECT COUNT(*) FROM rag_chat_session_lease lease JOIN sessions s ON s.owner_principal_id = lease.owner_principal_id AND s.session_id = lease.session_id WHERE lease.expires_at > clock_timestamp()) AS active_chat_count,
                  (SELECT COUNT(*) FROM rag_chat_history WHERE content_reference_index_complete = FALSE) AS unindexed_chat_count,
                  (SELECT COUNT(*) FROM rag_user_feedback WHERE content_reference_index_complete = FALSE) AS unindexed_feedback_count
                """;
        Object[] args = {collectionId, collectionId, collectionId, Long.toString(collectionId), collectionId};
        CountSnapshot snapshot = queryForAsyncCountSnapshot(sql, args, deadline, lease);
        if (snapshot.unindexedChatCount() > 0
                || snapshot.unindexedFeedbackCount() > 0) {
            throw conflict("Content reference indexes are incomplete");
        }
        if (snapshot.activeSyncCount() > 0 || snapshot.activeRepairCount() > 0
                || snapshot.activeChatCount() > 0) {
            throw conflict("Collection has active work or Chat sessions");
        }
        long derivedWithoutChatMemory = snapshot.embeddingCount() + snapshot.embeddingJobCount()
                + snapshot.versionCount() + snapshot.keywordChunkCount()
                + snapshot.embeddingStateCount() + snapshot.localStateCount()
                + snapshot.repairPreviewCount() + snapshot.repairItemCount()
                + snapshot.idempotencyCount() + snapshot.feedbackCount()
                + snapshot.feedbackReferenceCount() + snapshot.documentAuditCount()
                + snapshot.collectionAuditCount() + snapshot.chatHistoryCount()
                + snapshot.chatSummaryCount() + snapshot.chatTurnCount();
        if (snapshot.documentCount() > properties.getAsyncPreviewMaxDocuments()
                || derivedWithoutChatMemory > properties.getAsyncPreviewMaxDerivedRows()) {
            throw asyncPreviewLimitExceeded();
        }
        long memoryCount = countAsyncChatMemory(
                collectionId, derivedWithoutChatMemory, deadline, lease);
        long derivedCount = derivedWithoutChatMemory + memoryCount;
        return new Counts(
                snapshot.documentCount(), snapshot.externalCount(),
                snapshot.documentCount() - snapshot.externalCount(),
                snapshot.embeddingCount(), snapshot.embeddingJobCount(),
                snapshot.versionCount(), snapshot.keywordChunkCount(),
                snapshot.repairPreviewCount(), snapshot.repairItemCount(), derivedCount,
                snapshot.idempotencyCount(), snapshot.feedbackCount(),
                snapshot.feedbackReferenceCount(), snapshot.documentAuditCount(),
                snapshot.collectionAuditCount(), snapshot.relocationCount(),
                snapshot.sessionCount(), snapshot.chatHistoryCount(), memoryCount,
                snapshot.chatSummaryCount(), snapshot.chatTurnCount(),
                snapshot.activeSyncCount(), snapshot.activeRepairCount(),
                snapshot.activeChatCount(), snapshot.unindexedChatCount(),
                snapshot.unindexedFeedbackCount(), version(collection), chatFence(collection));
    }

    private CountSnapshot queryForAsyncCountSnapshot(
            String sql, Object[] args, Instant deadline, PreviewAdmissionLease lease) {
        return jdbcTemplate.query(asyncStatement(sql, args, deadline, lease),
                (ResultSetExtractor<CountSnapshot>) rs -> {
                    if (!rs.next()) {
                        throw new IllegalStateException("Async purge count query returned no row");
                    }
                    return new CountSnapshot(
                            rs.getLong("document_count"), rs.getLong("external_count"),
                            rs.getLong("embedding_count"), rs.getLong("embedding_job_count"),
                            rs.getLong("version_count"), rs.getLong("keyword_chunk_count"),
                            rs.getLong("repair_preview_count"), rs.getLong("repair_item_count"),
                            rs.getLong("idempotency_count"), rs.getLong("feedback_count"),
                            rs.getLong("feedback_reference_count"), rs.getLong("document_audit_count"),
                            rs.getLong("collection_audit_count"), rs.getLong("relocation_count"),
                            rs.getLong("session_count"), rs.getLong("chat_history_count"),
                            rs.getLong("chat_summary_count"), rs.getLong("chat_turn_count"),
                            rs.getLong("embedding_state_count"), rs.getLong("local_state_count"),
                            rs.getLong("active_sync_count"), rs.getLong("active_repair_count"),
                            rs.getLong("active_chat_count"), rs.getLong("unindexed_chat_count"),
                            rs.getLong("unindexed_feedback_count"));
                });
    }

    private String streamAsyncFingerprint(
            RagCollection collection, Instant deadline, PreviewAdmissionLease lease) {
        String sql = ASYNC_TARGET_CTES + """
                SELECT kind, value FROM (
                    SELECT 'D' AS kind, id::text AS value FROM docs
                    UNION ALL SELECT 'S', jsonb_build_array(owner_principal_id, session_id)::text FROM sessions
                    UNION ALL SELECT 'F', feedback_id::text FROM feedback_ids
                    UNION ALL SELECT 'A', id::text FROM document_audit_ids
                    UNION ALL SELECT 'A', id::text FROM collection_audit_ids
                    UNION ALL SELECT 'R', id::text FROM repair_ids
                    UNION ALL SELECT 'I', id::text FROM idempotency_ids
                ) targets ORDER BY kind, value
                """;
        MessageDigest digest = sha256Digest();
        int[] rows = {0};
        jdbcTemplate.query(asyncStatement(sql,
                new Object[]{collection.getId(), collection.getId(),
                                collection.getId(), Long.toString(collection.getId())},
                        deadline, lease),
                (RowCallbackHandler) rs -> {
                    if ((rows[0]++ % ASYNC_PREVIEW_FETCH_SIZE) == 0) {
                        requireAsyncPreviewLease(lease, deadline);
                    }
                    updateDigest(digest, rs.getString("kind"));
                    updateDigest(digest, rs.getString("value"));
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private long countAsyncChatMemory(
            long collectionId, long derivedWithoutChatMemory,
            Instant deadline, PreviewAdmissionLease lease) {
        String sql = ASYNC_TARGET_CTES + "SELECT owner_principal_id, session_id FROM sessions ORDER BY owner_principal_id NULLS FIRST, session_id";
        List<String> conversations = new ArrayList<>(ASYNC_PREVIEW_FETCH_SIZE);
        long[] total = {0};
        jdbcTemplate.query(asyncStatement(sql,
                        new Object[]{collectionId, collectionId, collectionId,
                                Long.toString(collectionId)}, deadline, lease),
                (RowCallbackHandler) rs -> {
                    String owner = rs.getString("owner_principal_id");
                    String session = rs.getString("session_id");
                    conversations.add(owner == null
                            ? session : memoryConversationId(owner, session));
                    if (conversations.size() >= ASYNC_PREVIEW_FETCH_SIZE) {
                        total[0] += countMemoryConversations(conversations, deadline, lease);
                        conversations.clear();
                        if (derivedWithoutChatMemory + total[0]
                                > properties.getAsyncPreviewMaxDerivedRows()) {
                            throw asyncPreviewLimitExceeded();
                        }
                        requireAsyncPreviewLease(lease, deadline);
                    }
                });
        if (!conversations.isEmpty()) {
            total[0] += countMemoryConversations(conversations, deadline, lease);
        }
        if (derivedWithoutChatMemory + total[0]
                > properties.getAsyncPreviewMaxDerivedRows()) {
            throw asyncPreviewLimitExceeded();
        }
        return total[0];
    }

    private long countMemoryConversations(
            List<String> conversations, Instant deadline, PreviewAdmissionLease lease) {
        String placeholders = placeholders(conversations.size());
        return jdbcTemplate.query(asyncStatement(
                        "SELECT COUNT(*) FROM spring_ai_chat_memory WHERE conversation_id IN ("
                                + placeholders + ")",
                        conversations.toArray(), deadline, lease),
                (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : 0L);
    }

    private PreparedStatementCreator asyncStatement(
            String sql, Object[] args, Instant deadline, PreviewAdmissionLease lease) {
        checkAsyncPreviewDeadline(deadline);
        long elapsedSinceAdmission = Duration.between(
                lease.admittedAt(), Instant.now()).toMillis();
        long remainingLeaseMillis = properties.getAsyncPreviewAdmissionLease()
                .toMillis() - elapsedSinceAdmission;
        long remainingDeadlineMillis = Duration.between(
                Instant.now(), deadline).toMillis();
        if (remainingLeaseMillis < 1_000 || remainingDeadlineMillis < 1_000) {
            throw asyncPreviewTimeout();
        }
        int timeoutSeconds = Math.max(1, (int) Math.min(
                Math.min(
                        properties.getAsyncPreviewStatementTimeout().toMillis(),
                        remainingLeaseMillis) / 1_000,
                remainingDeadlineMillis / 1_000));
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(ASYNC_PREVIEW_FETCH_SIZE);
            statement.setQueryTimeout(timeoutSeconds);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        };
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private void checkAsyncPreviewDeadline(Instant deadline) {
        if (!Instant.now().isBefore(deadline)) {
            throw asyncPreviewTimeout();
        }
    }

    private static CollectionPurgePreviewTimeoutException asyncPreviewTimeout() {
        return new CollectionPurgePreviewTimeoutException();
    }

    private static RagException asyncPreviewLimitExceeded() {
        return new RagException(
                ErrorCode.COLLECTION_PURGE_ASYNC_PREVIEW_LIMIT_EXCEEDED,
                "Collection purge preview exceeds the async scan limits");
    }

    private static boolean isPostgresStatementTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && "57014".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private CollectionPurgePreviewResponse createPreview(
            RagCollection collection, Plan plan, String owner, String executionMode) {
        return persistPreview(collection, plan.counts(), owner, executionMode,
                fingerprint(collection, plan));
    }

    private CollectionPurgePreviewResponse persistPreview(
            RagCollection collection, Counts c, String owner,
            String executionMode, String fingerprint) {
        UUID id = UUID.randomUUID();
        String token = token();
        Instant now = Instant.now();
        Instant previewDeadline = now.plus(properties.getConfirmationWindow());
        Instant operationDeadline = now.plus(properties.getOperationWindow());
        Instant resultExpiresAt = now.plus(properties.getResultRetention());
        jdbcTemplate.update("""
                INSERT INTO rag_collection_purge_preview (
                    id, owner_principal_id, collection_id, collection_key,
                    collection_version, chat_commit_fence_version,
                    confirmation_token_hash, fingerprint, status, execution_mode,
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
                    ?, ?, ?, ?, ?, ?, ?, ?, 'PREVIEWED', ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                id, owner, collection.getId(), collection.getCollectionKey(),
                version(collection), chatFence(collection),
                DigestUtils.sha256(token), fingerprint, executionMode,
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
                c.unindexedChatReferenceCount(), c.unindexedFeedbackReferenceCount(),
                Timestamp.from(previewDeadline), Timestamp.from(operationDeadline),
                Timestamp.from(resultExpiresAt));
        return response(id, collection, c, token, fingerprint,
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

    public LongRunningOperationResponse applyAsync(
            CollectionPurgeApplyRequest requestBody,
            List<String> idempotencyHeader,
            HttpServletRequest request) {
        authorization.requireAllowed(request);
        Objects.requireNonNull(requestBody, "request must not be null");
        requireCollectionKey(requestBody.collectionKey());
        String idempotencyKey = IdempotencyKeyValidator.normalize(idempotencyHeader);
        if (idempotencyKey == null) {
            throw new RagException(ErrorCode.MISSING_HEADER,
                    "Idempotency-Key is required for async Collection purge");
        }
        String owner = ChatPrincipal.from(request).id();
        Preview preview = loadPreview(requestBody.previewId(), owner);
        IntegrationObservationContext.addAuthorizedCollection(request, preview.collectionId());
        String requestFingerprint = asyncApplyFingerprint(requestBody);
        String idempotencyHash = IdempotencyKeyValidator.hash(idempotencyKey);
        UUID operationId = transactionTemplate.execute(status -> createAsyncApplyTransaction(
                requestBody, request, owner, idempotencyHash, requestFingerprint,
                preview.collectionId()));
        if (operationId == null) {
            throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Async Collection purge was not admitted");
        }
        if (operationService == null) {
            throw new IllegalStateException(
                    "Long-running operation service is not configured");
        }
        return operationService.get(operationId, request);
    }

    private UUID createAsyncApplyTransaction(
            CollectionPurgeApplyRequest requestBody,
            HttpServletRequest request,
            String owner,
            String idempotencyHash,
            String requestFingerprint,
            long collectionId) {
        List<ExistingPurgeOperation> existing = jdbcTemplate.query("""
                SELECT id, request_fingerprint_sha256
                FROM rag_long_running_operations
                WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                  AND operation_kind = 'COLLECTION_PURGE' AND collection_id = ?
                """,
                (rs, row) -> new ExistingPurgeOperation(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_fingerprint_sha256")),
                owner, idempotencyHash, collectionId);
        if (!existing.isEmpty()) {
            ExistingPurgeOperation replay = existing.getFirst();
            if (!constantEquals(replay.fingerprint(), requestFingerprint)) {
                throw new RagException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was reused with a different Collection purge request");
            }
            return replay.id();
        }

        Preview preview = loadPreview(requestBody.previewId(), owner);
        if (preview.collectionId() != collectionId
                || !preview.collectionKey().equals(requestBody.collectionKey())) {
            throw conflict("Collection purge preview does not match the request");
        }
        IntegrationObservationContext.addAuthorizedCollection(request, collectionId);
        validateFrozenRequest(preview, requestBody, true);
        requirePreviewApplicable(preview);
        List<UUID> alreadyConsumed = jdbcTemplate.query("""
                SELECT id FROM rag_long_running_operations
                WHERE source_preview_id = ? AND operation_kind = 'COLLECTION_PURGE'
                """, (rs, row) -> rs.getObject(1, UUID.class), preview.id());
        if (!alreadyConsumed.isEmpty()) {
            throw conflict("Collection purge preview has already been submitted");
        }

        UUID operationId = UUID.randomUUID();
        String progress = json(Map.of(
                "phase", "FENCE",
                "sourcePreviewId", preview.id().toString(),
                "documentCount", countPreviewDocuments(preview.id())));
        List<UUID> inserted;
        try {
            inserted = jdbcTemplate.query("""
                    INSERT INTO rag_long_running_operations (
                        id, owner_principal_id, resource_type, collection_id,
                        operation_kind, idempotency_key_sha256,
                        request_fingerprint_sha256, status, progress_payload,
                        source_preview_id, total_count
                    ) VALUES (?, ?, 'COLLECTION', ?, 'COLLECTION_PURGE', ?, ?,
                              'QUEUED', ?::jsonb, ?, ?)
                    ON CONFLICT (owner_principal_id, idempotency_key_sha256,
                                 operation_kind, collection_id) DO NOTHING
                    RETURNING id
                    """,
                    (rs, row) -> rs.getObject(1, UUID.class),
                    operationId, owner, collectionId, idempotencyHash,
                    requestFingerprint, progress, preview.id(),
                    countPreviewDocuments(preview.id()));
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw conflict("Collection purge preview has already been submitted");
        }
        if (inserted.isEmpty()) {
            ExistingPurgeOperation replay = jdbcTemplate.queryForObject("""
                    SELECT id, request_fingerprint_sha256
                    FROM rag_long_running_operations
                    WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                      AND operation_kind = 'COLLECTION_PURGE' AND collection_id = ?
                    """,
                    (rs, row) -> new ExistingPurgeOperation(
                            rs.getObject("id", UUID.class),
                            rs.getString("request_fingerprint_sha256")),
                    owner, idempotencyHash, collectionId);
            if (replay == null || !constantEquals(replay.fingerprint(), requestFingerprint)) {
                throw new RagException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was reused with a different Collection purge request");
            }
            return replay.id();
        }

        reserveOperationCapacity(owner);
        try {
            jdbcTemplate.update("""
                    INSERT INTO rag_collection_operation_slot (
                        collection_id, holder_kind, operation_id)
                    VALUES (?, 'ASYNC_PURGE', ?)
                    """, collectionId, operationId);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new RagException(ErrorCode.COLLECTION_OPERATION_IN_PROGRESS,
                    "Another durable operation is active for this Collection");
        }
        int claimed = jdbcTemplate.update("""
                UPDATE rag_collection_purge_preview
                SET status = 'APPLYING', apply_lease_owner_hash = ?,
                    apply_lease_expires_at = operation_deadline
                WHERE id = ? AND owner_principal_id = ? AND execution_mode = 'ASYNC'
                  AND status = 'PREVIEWED'
                  AND preview_deadline >= clock_timestamp()
                  AND operation_deadline >= clock_timestamp()
                """, DigestUtils.sha256(operationId.toString()),
                preview.id(), owner);
        if (claimed != 1) {
            throw expired();
        }
        return operationId;
    }

    private void reserveOperationCapacity(String owner) {
        jdbcTemplate.update("""
                INSERT INTO rag_long_running_operation_owner_capacity (
                    owner_principal_id, active_count)
                VALUES (?, 0) ON CONFLICT (owner_principal_id) DO NOTHING
                """, owner);
        if (jdbcTemplate.update("""
                UPDATE rag_long_running_operation_owner_capacity
                SET active_count = active_count + 1, updated_at = clock_timestamp()
                WHERE owner_principal_id = ? AND active_count < ?
                """, owner, OPERATION_OWNER_CAPACITY) != 1) {
            throw new LongRunningOperationCapacityException(
                    "Owner operation capacity is exhausted");
        }
        if (jdbcTemplate.update("""
                UPDATE rag_long_running_operation_global_capacity
                SET active_count = active_count + 1, updated_at = clock_timestamp()
                WHERE singleton_id = 1 AND active_count < ?
                """, OPERATION_GLOBAL_CAPACITY) != 1) {
            throw new LongRunningOperationCapacityException(
                    "Global operation capacity is exhausted");
        }
    }

    private long countPreviewDocuments(UUID previewId) {
        Long result = jdbcTemplate.queryForObject("""
                SELECT document_count FROM rag_collection_purge_preview WHERE id = ?
                """, Long.class, previewId);
        return result == null ? 0 : result;
    }

    public void processAsyncWorkerBatch(CollectionPurgeOperationWorker.Claim claim) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('statement_timeout', ?, true)",
                    String.class,
                    properties.getAsyncBatchStatementTimeout().toMillis() + "ms");
            if (!renewAsyncWorkerLease(claim)) {
                return;
            }
            AsyncWorkerOperation operation = loadAsyncWorkerOperation(claim);
            if (operation == null) {
                return;
            }
            if (operation.cancelRequested() && operation.fenceCompletedAt() == null) {
                cancelAsyncOperationBeforeFence(operation, claim);
                return;
            }
            if (operation.fenceCompletedAt() == null) {
                fenceAsyncOperation(operation, claim);
                return;
            }
            if (operation.phase().startsWith("MANIFEST_")) {
                populateAsyncManifestPage(operation, claim);
                return;
            }
            processAsyncDeletePhase(operation, claim);
        });
    }

    private AsyncWorkerOperation loadAsyncWorkerOperation(
            CollectionPurgeOperationWorker.Claim claim) {
        List<AsyncWorkerOperation> rows = jdbcTemplate.query("""
                SELECT id, owner_principal_id, collection_id, source_preview_id,
                       COALESCE(cursor_phase, progress_payload->>'phase', 'FENCE') AS phase,
                       COALESCE(progress_payload->>'manifestCursor', '') AS manifest_cursor,
                       cancel_requested_at IS NOT NULL AS cancel_requested,
                       purge_fence_completed_at
                FROM rag_long_running_operations
                WHERE id = ? AND operation_kind = 'COLLECTION_PURGE'
                  AND status = 'RUNNING' AND lease_token = ?
                """, (rs, row) -> new AsyncWorkerOperation(
                rs.getObject("id", UUID.class),
                rs.getString("owner_principal_id"),
                rs.getLong("collection_id"),
                rs.getObject("source_preview_id", UUID.class),
                rs.getString("phase"),
                rs.getString("manifest_cursor"),
                rs.getBoolean("cancel_requested"),
                rs.getTimestamp("purge_fence_completed_at") == null ? null
                        : rs.getTimestamp("purge_fence_completed_at").toInstant()),
                claim.operationId(), claim.leaseToken());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean renewAsyncWorkerLease(CollectionPurgeOperationWorker.Claim claim) {
        int renewed = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, properties.getAsyncOperationLease().toMillis(),
                claim.operationId(), claim.leaseToken());
        if (renewed != 1) {
            return false;
        }
        int slotRenewed = jdbcTemplate.update("""
                UPDATE rag_collection_purge_worker_slots
                SET lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE slot_id = ? AND operation_id = ? AND lease_token = ?
                """, properties.getAsyncOperationLease().toMillis(),
                claim.slotId(), claim.operationId(), claim.leaseToken());
        if (slotRenewed != 1) {
            throw new IllegalStateException("Collection Purge worker slot lease was lost");
        }
        return true;
    }

    private void fenceAsyncOperation(
            AsyncWorkerOperation operation,
            CollectionPurgeOperationWorker.Claim claim) {
        Preview preview = loadPreview(operation.previewId(), operation.ownerPrincipalId());
        if (!Instant.now().isBefore(preview.operationDeadline())
                || !verifyAsyncWorkerFingerprint(preview)) {
            finishAsyncOperation(operation, claim, "STALE", "PREVIEW_CHANGED");
            return;
        }
        Integer snapshotMatches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rag_collection
                WHERE id = ? AND version = ? AND chat_commit_fence_version = ?
                  AND purged_at IS NULL
                """, Integer.class, preview.collectionId(),
                preview.collectionVersion(), preview.chatFenceVersion());
        if (snapshotMatches == null || snapshotMatches != 1) {
            finishAsyncOperation(operation, claim, "STALE", "COLLECTION_CHANGED");
            return;
        }
        int operationFenced = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET purge_fence_completed_at = clock_timestamp(),
                    cursor_phase = 'MANIFEST_D',
                    progress_payload = jsonb_set(progress_payload, '{phase}', '"MANIFEST_D"'::jsonb),
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                  AND cancel_requested_at IS NULL
                  AND purge_fence_completed_at IS NULL
                """, claim.operationId(), claim.leaseToken());
        if (operationFenced != 1) {
            return;
        }
        int collectionFenced = jdbcTemplate.update("""
                UPDATE rag_collection
                SET deleted = TRUE, enabled = FALSE,
                    deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                    version = version + 1
                WHERE id = ? AND purged_at IS NULL AND version = ?
                  AND chat_commit_fence_version = ?
                """, preview.collectionId(), preview.collectionVersion(),
                preview.chatFenceVersion());
        if (collectionFenced != 1) {
            finishAsyncOperation(operation, claim, "STALE", "COLLECTION_CHANGED");
            return;
        }
        releaseAsyncWorkerSlice(claim, "MANIFEST_D", "");
    }

    private boolean verifyAsyncWorkerFingerprint(Preview preview) {
        String targetDigest = streamAsyncWorkerTargetDigest(preview.collectionId());
        Counts counts = loadPreviewCounts(preview);
        Map<String, Object> fingerprintBody = new LinkedHashMap<>();
        fingerprintBody.put("version", 1);
        fingerprintBody.put("collectionId", preview.collectionId());
        fingerprintBody.put("collectionKey", preview.collectionKey());
        fingerprintBody.put("collectionVersion", preview.collectionVersion());
        fingerprintBody.put("chatCommitFenceVersion", preview.chatFenceVersion());
        fingerprintBody.put("counts", counts);
        fingerprintBody.put("targetDigest", targetDigest);
        return constantEquals(preview.fingerprint(), DigestUtils.sha256(json(fingerprintBody)));
    }

    private String streamAsyncWorkerTargetDigest(long collectionId) {
        String sql = ASYNC_TARGET_CTES + """
                SELECT kind, value FROM (
                    SELECT 'D' AS kind, id::text AS value FROM docs
                    UNION ALL SELECT 'S', jsonb_build_array(owner_principal_id, session_id)::text FROM sessions
                    UNION ALL SELECT 'F', feedback_id::text FROM feedback_ids
                    UNION ALL SELECT 'A', id::text FROM document_audit_ids
                    UNION ALL SELECT 'A', id::text FROM collection_audit_ids
                    UNION ALL SELECT 'R', id::text FROM repair_ids
                    UNION ALL SELECT 'I', id::text FROM idempotency_ids
                ) targets ORDER BY kind, value
                """;
        MessageDigest digest = sha256Digest();
        Instant deadline = Instant.now().plus(properties.getAsyncBatchDeadline());
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(ASYNC_PREVIEW_FETCH_SIZE);
            statement.setQueryTimeout(Math.toIntExact(Math.max(1,
                    properties.getAsyncBatchStatementTimeout().toSeconds())));
            statement.setObject(1, collectionId);
            statement.setObject(2, collectionId);
            statement.setObject(3, collectionId);
            statement.setObject(4, Long.toString(collectionId));
            return statement;
        }, (RowCallbackHandler) rs -> {
            if (!Instant.now().isBefore(deadline)) {
                throw asyncPreviewTimeout();
            }
            updateDigest(digest, rs.getString("kind"));
            updateDigest(digest, rs.getString("value"));
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private Counts loadPreviewCounts(Preview preview) {
        return jdbcTemplate.queryForObject("""
                SELECT document_count, external_document_count, local_document_count,
                       embedding_count, embedding_job_count, version_count,
                       keyword_chunk_count, repair_preview_count, repair_item_count,
                       derived_row_count, document_idempotency_operation_count,
                       feedback_count, feedback_document_reference_count,
                       document_audit_count, collection_audit_count,
                       relocation_marker_count, affected_chat_session_count,
                       chat_history_count, chat_memory_count, chat_summary_count,
                       chat_turn_operation_count, active_sync_run_count,
                       active_derivation_repair_count, active_chat_session_count,
                       unindexed_chat_reference_count, unindexed_feedback_reference_count
                FROM rag_collection_purge_preview WHERE id = ?
                """, (rs, row) -> new Counts(
                rs.getLong("document_count"), rs.getLong("external_document_count"),
                rs.getLong("local_document_count"), rs.getLong("embedding_count"),
                rs.getLong("embedding_job_count"), rs.getLong("version_count"),
                rs.getLong("keyword_chunk_count"), rs.getLong("repair_preview_count"),
                rs.getLong("repair_item_count"), rs.getLong("derived_row_count"),
                rs.getLong("document_idempotency_operation_count"),
                rs.getLong("feedback_count"), rs.getLong("feedback_document_reference_count"),
                rs.getLong("document_audit_count"), rs.getLong("collection_audit_count"),
                rs.getLong("relocation_marker_count"), rs.getLong("affected_chat_session_count"),
                rs.getLong("chat_history_count"), rs.getLong("chat_memory_count"),
                rs.getLong("chat_summary_count"), rs.getLong("chat_turn_operation_count"),
                rs.getLong("active_sync_run_count"), rs.getLong("active_derivation_repair_count"),
                rs.getLong("active_chat_session_count"),
                rs.getLong("unindexed_chat_reference_count"),
                rs.getLong("unindexed_feedback_reference_count"),
                preview.collectionVersion(), preview.chatFenceVersion()), preview.id());
    }

    private void populateAsyncManifestPage(
            AsyncWorkerOperation operation,
            CollectionPurgeOperationWorker.Claim claim) {
        int kindIndex = ASYNC_MANIFEST_KINDS.indexOf(
                operation.phase().substring("MANIFEST_".length()));
        if (kindIndex < 0) {
            throw new IllegalStateException("Unknown Collection Purge manifest phase");
        }
        String kind = ASYNC_MANIFEST_KINDS.get(kindIndex);
        int pageSize = properties.getAsyncManifestBatchSize();
        String sql = ASYNC_TARGET_CTES + """
                , targets AS (
                    SELECT DISTINCT kind, value FROM (
                        SELECT 'D' AS kind, id::text AS value FROM docs
                        UNION ALL SELECT 'S', jsonb_build_array(owner_principal_id, session_id)::text FROM sessions
                        UNION ALL SELECT 'F', feedback_id::text FROM feedback_ids
                        UNION ALL SELECT 'A', id::text FROM document_audit_ids
                        UNION ALL SELECT 'A', id::text FROM collection_audit_ids
                        UNION ALL SELECT 'R', id::text FROM repair_ids
                        UNION ALL SELECT 'I', id::text FROM idempotency_ids
                    ) all_targets
                ), page AS (
                    SELECT value FROM targets
                    WHERE kind = ? AND value > ?
                    ORDER BY value LIMIT ?
                )
                INSERT INTO rag_collection_purge_operation_targets (
                    operation_id, target_kind, target_key)
                SELECT ?, ?, value FROM page
                ON CONFLICT DO NOTHING
                RETURNING target_key
                """;
        List<String> inserted = jdbcTemplate.query(sql, (rs, row) -> rs.getString(1),
                operation.collectionId(), operation.collectionId(),
                operation.collectionId(), Long.toString(operation.collectionId()),
                kind, operation.manifestCursor(), pageSize,
                operation.id(), kind);
        String nextPhase = operation.phase();
        String nextCursor = inserted.isEmpty()
                ? operation.manifestCursor() : inserted.getLast();
        if (inserted.size() < pageSize) {
            nextPhase = kindIndex + 1 < ASYNC_MANIFEST_KINDS.size()
                    ? "MANIFEST_" + ASYNC_MANIFEST_KINDS.get(kindIndex + 1)
                    : "DELETE_" + ASYNC_DELETE_PHASES.getFirst();
            nextCursor = "";
        }
        releaseAsyncWorkerSlice(claim, nextPhase, nextCursor);
    }

    private void cancelAsyncOperationBeforeFence(
            AsyncWorkerOperation operation,
            CollectionPurgeOperationWorker.Claim claim) {
        finishAsyncOperation(operation, claim, "CANCELLED", "CLIENT_CANCELLED");
    }

    private void finishAsyncOperation(
            AsyncWorkerOperation operation,
            CollectionPurgeOperationWorker.Claim claim,
            String terminalStatus,
            String reason) {
        int completed = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = ?, terminal_reason = ?,
                    result_payload = jsonb_build_object('status', ?, 'reason', ?),
                    error_code = CASE WHEN ? = 'STALE'
                        THEN 'COLLECTION_PURGE_STALE' ELSE error_code END,
                    finished_at = clock_timestamp(),
                    result_expires_at = clock_timestamp() + INTERVAL '7 days',
                    tombstone_expires_at = GREATEST(
                        created_at + INTERVAL '30 days',
                        clock_timestamp() + INTERVAL '7 days'),
                    purge_fence_completed_at = NULL,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    next_retry_at = NULL, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, terminalStatus, reason, terminalStatus, reason, terminalStatus,
                claim.operationId(), claim.leaseToken());
        if (completed != 1) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE rag_long_running_operation_owner_capacity
                SET active_count = GREATEST(0, active_count - 1),
                    updated_at = clock_timestamp()
                WHERE owner_principal_id = ?
                """, operation.ownerPrincipalId());
        jdbcTemplate.update("""
                UPDATE rag_long_running_operation_global_capacity
                SET active_count = GREATEST(0, active_count - 1),
                    updated_at = clock_timestamp()
                WHERE singleton_id = 1
                """);
        jdbcTemplate.update("""
                DELETE FROM rag_collection_operation_slot
                WHERE collection_id = ? AND operation_id = ?
                """, operation.collectionId(), operation.id());
        jdbcTemplate.update("""
                UPDATE rag_collection_purge_preview
                SET status = 'EXPIRED', completed_at = clock_timestamp(),
                    apply_lease_owner_hash = NULL, apply_lease_expires_at = NULL
                WHERE id = ? AND status = 'APPLYING'
                """, operation.previewId());
        releaseAsyncWorkerSlot(claim);
    }

    private void releaseAsyncWorkerSlice(
            CollectionPurgeOperationWorker.Claim claim,
            String nextPhase,
            String manifestCursor) {
        int updated = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET cursor_phase = ?,
                    progress_payload = jsonb_set(
                        jsonb_set(progress_payload, '{phase}', to_jsonb(?::text), true),
                        '{manifestCursor}', to_jsonb(?::text), true),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                """, nextPhase, nextPhase, manifestCursor,
                claim.operationId(), claim.leaseToken());
        if (updated != 1) {
            throw new IllegalStateException("Collection Purge worker lease was lost");
        }
        releaseAsyncWorkerSlot(claim);
    }

    private void releaseAsyncWorkerSlot(CollectionPurgeOperationWorker.Claim claim) {
        jdbcTemplate.update("""
                UPDATE rag_collection_purge_worker_slots
                SET operation_id = NULL, lease_owner = NULL,
                    lease_token = NULL, lease_expires_at = NULL,
                    updated_at = clock_timestamp()
                WHERE slot_id = ? AND operation_id = ? AND lease_token = ?
                """, claim.slotId(), claim.operationId(), claim.leaseToken());
    }

    private void processAsyncDeletePhase(
            AsyncWorkerOperation operation,
            CollectionPurgeOperationWorker.Claim claim) {
        String phase = operation.phase().startsWith("DELETE_")
                ? operation.phase().substring("DELETE_".length()) : operation.phase();
        if ("RETIRE".equals(phase)) {
            retireAsyncCollection(operation, claim);
            return;
        }
        int rowLimit = properties.getAsyncBatchMaxRows();
        if ("CHAT_MEMORY".equals(phase)) {
            MemoryDeleteBatch batch = deleteAsyncChatMemory(
                    operation.id(), operation.manifestCursor(), rowLimit);
            if (batch.hasMoreInPage()) {
                releaseAsyncWorkerSlice(claim, "DELETE_" + phase, batch.cursor());
            } else {
                releaseAsyncWorkerSlice(claim,
                        "DELETE_" + nextDeletePhase(phase), batch.cursor());
            }
            return;
        }
        int deleted = deleteAsyncPhaseRows(operation.id(), phase, rowLimit);
        String nextPhase = deleted < rowLimit ? nextDeletePhase(phase) : phase;
        releaseAsyncWorkerSlice(claim, "DELETE_" + nextPhase, "");
    }

    private int deleteAsyncPhaseRows(UUID operationId, String phase, int limit) {
        return switch (phase) {
            case "CHAT_REFERENCES" -> deleteAsyncChatReferences(operationId, limit);
            case "CHAT_HISTORY" -> deleteAsyncSessionRows(
                    operationId, "rag_chat_history", "owner_principal_id", limit);
            case "CHAT_SUMMARIES" -> deleteAsyncSessionRows(
                    operationId, "rag_chat_memory_summary", "owner_principal_id", limit);
            case "CHAT_TURNS" -> deleteAsyncSessionRows(
                    operationId, "rag_chat_turn_operations", "owner_principal_id", limit);
            case "CHAT_LEASES" -> deleteAsyncSessionRows(
                    operationId, "rag_chat_session_lease", "owner_principal_id", limit);
            case "FEEDBACK_REFERENCES" -> deleteAsyncManifestRows(
                    operationId, "rag_user_feedback_document", "feedback_id",
                    "F", "bigint", limit);
            case "FEEDBACK" -> deleteAsyncManifestRows(
                    operationId, "rag_user_feedback", "id", "F", "bigint", limit);
            case "AUDIT" -> deleteAsyncManifestRows(
                    operationId, "rag_audit_log", "id", "A", "bigint", limit);
            case "REPAIR_ITEMS" -> deleteAsyncManifestRows(
                    operationId, "rag_derivation_repair_items", "repair_id",
                    "R", "uuid", limit);
            case "REPAIR_PREVIEWS" -> deleteAsyncManifestRows(
                    operationId, "rag_derivation_repair_previews", "id",
                    "R", "uuid", limit);
            case "IDEMPOTENCY" -> deleteAsyncManifestRows(
                    operationId, "rag_document_idempotency_operations", "id",
                    "I", "bigint", limit);
            case "EMBEDDINGS" -> deleteAsyncManifestRows(
                    operationId, "rag_embeddings", "document_id", "D", "bigint", limit);
            case "PROCESSING_STAGES" -> deleteAsyncProcessingStages(operationId, limit);
            case "OPERATION_RUN_REFS" -> deleteAsyncOperationRunRefs(operationId, limit);
            case "PROCESSING_RUNS" -> deleteAsyncManifestRows(
                    operationId, "rag_document_processing_runs", "document_id",
                    "D", "bigint", limit);
            case "EMBEDDING_JOBS" -> deleteAsyncManifestRows(
                    operationId, "rag_embedding_jobs", "document_id", "D", "bigint", limit);
            case "DOCUMENT_VERSIONS" -> deleteAsyncManifestRows(
                    operationId, "rag_document_versions", "document_id", "D", "bigint", limit);
            case "DOCUMENT_CHUNKS" -> deleteAsyncManifestRows(
                    operationId, "rag_document_chunks", "document_id", "D", "bigint", limit);
            case "LOCAL_INDEX_STATE" -> deleteAsyncManifestRows(
                    operationId, "rag_document_local_index_state", "document_id",
                    "D", "bigint", limit);
            case "EMBEDDING_STATE" -> deleteAsyncManifestRows(
                    operationId, "rag_document_embedding_state", "document_id",
                    "D", "bigint", limit);
            case "RELOCATED_ADDRESSES" -> deleteAsyncManifestRows(
                    operationId, "rag_document_relocated_addresses", "document_id",
                    "D", "bigint", limit);
            case "DOCUMENTS" -> deleteAsyncManifestRows(
                    operationId, "rag_documents", "id", "D", "bigint",
                    Math.min(limit, properties.getAsyncBatchMaxDocuments()));
            default -> throw new IllegalStateException(
                    "Unknown Collection Purge delete phase: " + phase);
        };
    }

    private int deleteAsyncManifestRows(
            UUID operationId, String table, String column,
            String kind, String valueType, int limit) {
        String sql = """
                WITH candidates AS (
                    SELECT child.ctid FROM %s child
                    JOIN rag_collection_purge_operation_targets target
                      ON target.operation_id = ? AND target.target_kind = ?
                     AND child.%s = target.target_key::%s
                    LIMIT ?
                )
                DELETE FROM %s child USING candidates
                WHERE child.ctid = candidates.ctid
                """.formatted(table, column, valueType, table);
        return jdbcTemplate.update(sql, operationId, kind, limit);
    }

    private int deleteAsyncChatReferences(UUID operationId, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT reference.ctid
                    FROM rag_chat_history_source_document reference
                    JOIN rag_chat_history history ON history.id = reference.history_id
                    JOIN rag_collection_purge_operation_targets target
                      ON target.operation_id = ? AND target.target_kind = 'S'
                     AND history.session_id = target.target_key::jsonb->>1
                     AND history.owner_principal_id IS NOT DISTINCT FROM
                         target.target_key::jsonb->>0
                    LIMIT ?
                )
                DELETE FROM rag_chat_history_source_document reference
                USING candidates WHERE reference.ctid = candidates.ctid
                """, operationId, limit);
    }

    private int deleteAsyncSessionRows(
            UUID operationId, String table, String ownerColumn, int limit) {
        String sql = """
                WITH candidates AS (
                    SELECT child.ctid FROM %s child
                    JOIN rag_collection_purge_operation_targets target
                      ON target.operation_id = ? AND target.target_kind = 'S'
                     AND child.session_id = target.target_key::jsonb->>1
                     AND child.%s IS NOT DISTINCT FROM target.target_key::jsonb->>0
                    LIMIT ?
                )
                DELETE FROM %s child USING candidates
                WHERE child.ctid = candidates.ctid
                """.formatted(table, ownerColumn, table);
        return jdbcTemplate.update(sql, operationId, limit);
    }

    private MemoryDeleteBatch deleteAsyncChatMemory(
            UUID operationId, String cursor, int rowLimit) {
        List<String> keys = jdbcTemplate.query("""
                SELECT target_key
                FROM rag_collection_purge_operation_targets
                WHERE operation_id = ? AND target_kind = 'S' AND target_key > ?
                ORDER BY target_key LIMIT ?
                """, (rs, row) -> rs.getString(1), operationId,
                cursor, ASYNC_PREVIEW_FETCH_SIZE);
        if (keys.isEmpty()) {
            return new MemoryDeleteBatch(0, cursor, false);
        }
        List<String> conversations = new ArrayList<>(keys.size());
        for (String key : keys) {
            try {
                JsonNode session = objectMapper.readTree(key);
                String owner = session.path(0).isNull() ? null : session.path(0).asText();
                String sessionId = session.path(1).asText();
                conversations.add(owner == null ? sessionId
                        : memoryConversationId(owner, sessionId));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                        "Invalid Collection Purge session manifest", exception);
            }
        }
        String sql = """
                DELETE FROM spring_ai_chat_memory
                WHERE ctid IN (
                    SELECT ctid FROM spring_ai_chat_memory
                    WHERE conversation_id IN (%s) LIMIT ?
                )
                """.formatted(placeholders(conversations.size()));
        int deleted = jdbcTemplate.update(sql, concat(conversations, rowLimit));
        boolean samePageRemains = deleted >= rowLimit;
        return new MemoryDeleteBatch(deleted,
                samePageRemains ? cursor : keys.getLast(), samePageRemains);
    }

    private int deleteAsyncProcessingStages(UUID operationId, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT stage.ctid
                    FROM rag_document_processing_stages stage
                    JOIN rag_document_processing_runs run ON run.id = stage.run_id
                    JOIN rag_collection_purge_operation_targets target
                      ON target.operation_id = ? AND target.target_kind = 'D'
                     AND run.document_id = target.target_key::bigint
                    LIMIT ?
                )
                DELETE FROM rag_document_processing_stages stage
                USING candidates WHERE stage.ctid = candidates.ctid
                """, operationId, limit);
    }

    private int deleteAsyncOperationRunRefs(UUID operationId, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT reference.ctid
                    FROM rag_long_running_operation_run_refs reference
                    JOIN rag_document_processing_runs run ON run.id = reference.run_id
                    JOIN rag_collection_purge_operation_targets target
                      ON target.operation_id = ? AND target.target_kind = 'D'
                     AND run.document_id = target.target_key::bigint
                    LIMIT ?
                )
                DELETE FROM rag_long_running_operation_run_refs reference
                USING candidates WHERE reference.ctid = candidates.ctid
                """, operationId, limit);
    }

    private String nextDeletePhase(String current) {
        int index = ASYNC_DELETE_PHASES.indexOf(current);
        if (index < 0 || index + 1 >= ASYNC_DELETE_PHASES.size()) {
            throw new IllegalStateException("Unknown or terminal purge phase: " + current);
        }
        return ASYNC_DELETE_PHASES.get(index + 1);
    }

    private void retireAsyncCollection(
            AsyncWorkerOperation operation,
            CollectionPurgeOperationWorker.Claim claim) {
        Long remainingDocuments = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_collection_purge_operation_targets target
                JOIN rag_documents document ON document.id = target.target_key::bigint
                WHERE target.operation_id = ? AND target.target_kind = 'D'
                """, Long.class, operation.id());
        if (remainingDocuments != null && remainingDocuments != 0) {
            throw new IllegalStateException("Collection Purge left target documents behind");
        }
        Preview preview = loadPreview(operation.previewId(), operation.ownerPrincipalId());
        Counts counts = loadPreviewCounts(preview);
        markCollectionRetired(preview);
        CollectionPurgeResultResponse result = buildRetiredResult(preview, counts);
        String resultPayload = json(result);
        String applyLease = DigestUtils.sha256(operation.id().toString());
        completePurgePreview(preview, operation.ownerPrincipalId(), applyLease, resultPayload);
        writePurgeAuditLog(preview, Math.toIntExact(counts.documentCount()));
        int completed = jdbcTemplate.update("""
                UPDATE rag_long_running_operations
                SET status = 'SUCCEEDED', terminal_reason = 'COLLECTION_RETIRED',
                    result_payload = ?::jsonb,
                    progress_payload = jsonb_set(progress_payload, '{phase}', '"RETIRED"'::jsonb),
                    processed_count = total_count,
                    finished_at = clock_timestamp(),
                    result_expires_at = clock_timestamp() + INTERVAL '7 days',
                    tombstone_expires_at = GREATEST(
                        created_at + INTERVAL '30 days',
                        clock_timestamp() + INTERVAL '7 days'),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
                  AND purge_fence_completed_at IS NOT NULL
                """, resultPayload, operation.id(), claim.leaseToken());
        if (completed != 1) {
            throw new IllegalStateException("Collection Purge operation lease was lost at retirement");
        }
        jdbcTemplate.update("""
                UPDATE rag_long_running_operation_owner_capacity
                SET active_count = GREATEST(0, active_count - 1),
                    updated_at = clock_timestamp()
                WHERE owner_principal_id = ?
                """, operation.ownerPrincipalId());
        jdbcTemplate.update("""
                UPDATE rag_long_running_operation_global_capacity
                SET active_count = GREATEST(0, active_count - 1),
                    updated_at = clock_timestamp()
                WHERE singleton_id = 1
                """);
        jdbcTemplate.update("""
                DELETE FROM rag_collection_operation_slot
                WHERE collection_id = ? AND operation_id = ?
                """, operation.collectionId(), operation.id());
        releaseAsyncWorkerSlot(claim);
    }

    public void recordAsyncWorkerFailure(CollectionPurgeOperationWorker.Claim claim) {
        List<AsyncWorkerFailure> failures = jdbcTemplate.query("""
                UPDATE rag_long_running_operations
                SET status = CASE WHEN attempt_count + 1 >= ?
                                  THEN 'DEGRADED' ELSE 'RETRY_WAIT' END,
                    attempt_count = attempt_count + 1,
                    next_retry_at = CASE WHEN attempt_count + 1 >= ? THEN NULL
                        ELSE clock_timestamp()
                            + (LEAST(60, 5 * (attempt_count + 1)) * interval '1 second')
                        END,
                    error_code = 'COLLECTION_PURGE_WORKER_FAILURE',
                    error_payload = '{"code":"COLLECTION_PURGE_WORKER_FAILURE"}'::jsonb,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND operation_kind = 'COLLECTION_PURGE'
                  AND status = 'RUNNING' AND lease_token = ?
                RETURNING owner_principal_id, status
                """, (rs, row) -> new AsyncWorkerFailure(
                rs.getString("owner_principal_id"), rs.getString("status")),
                properties.getAsyncMaxCoordinatorAttempts(),
                properties.getAsyncMaxCoordinatorAttempts(),
                claim.operationId(), claim.leaseToken());
        if (failures.isEmpty()) {
            return;
        }
        AsyncWorkerFailure failure = failures.getFirst();
        if ("DEGRADED".equals(failure.status())) {
            jdbcTemplate.update("""
                    UPDATE rag_long_running_operation_owner_capacity
                    SET active_count = GREATEST(0, active_count - 1),
                        updated_at = clock_timestamp()
                    WHERE owner_principal_id = ?
                    """, failure.ownerPrincipalId());
        }
        jdbcTemplate.update("""
                UPDATE rag_collection_purge_worker_slots
                SET operation_id = NULL, lease_owner = NULL,
                    lease_token = NULL, lease_expires_at = NULL,
                    updated_at = clock_timestamp()
                WHERE slot_id = ? AND operation_id = ? AND lease_token = ?
                """, claim.slotId(), claim.operationId(), claim.leaseToken());
    }

    private String asyncApplyFingerprint(CollectionPurgeApplyRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", 1);
        fields.put("collectionKey", request.collectionKey());
        fields.put("previewId", request.previewId());
        fields.put("confirmationTokenHash", DigestUtils.sha256(request.confirmationToken()));
        fields.put("fingerprint", request.fingerprint());
        fields.put("expectedCollectionVersion", request.expectedCollectionVersion());
        fields.put("expectedChatCommitFenceVersion", request.expectedChatCommitFenceVersion());
        return DigestUtils.sha256(json(fields));
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
        acquireSynchronousCollectionSlot(preview);
        String lease = claimApplyLease(preview, owner);
        fenceCollection(preview);
        Plan current = requireUnchangedPlan(preview);
        int deletedDocuments = deletePurgeTargets(preview, current);
        CollectionPurgeResultResponse result = retireCollection(
                preview, owner, lease, current, deletedDocuments);
        releaseSynchronousCollectionSlot(preview);
        return result;
    }

    private void acquireSynchronousCollectionSlot(Preview preview) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO rag_collection_operation_slot (
                        collection_id, holder_kind, operation_id)
                    VALUES (?, 'SYNC_PURGE', NULL)
                    """, preview.collectionId());
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new RagException(
                    ErrorCode.COLLECTION_OPERATION_IN_PROGRESS,
                    "Another Collection operation is already active");
        }
    }

    private void releaseSynchronousCollectionSlot(Preview preview) {
        int released = jdbcTemplate.update("""
                DELETE FROM rag_collection_operation_slot
                WHERE collection_id = ? AND holder_kind = 'SYNC_PURGE'
                  AND operation_id IS NULL
                """, preview.collectionId());
        if (released != 1) {
            throw conflict("Collection purge lost its operation slot");
        }
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
        return buildRetiredResult(preview, current.counts());
    }

    private CollectionPurgeResultResponse buildRetiredResult(
            Preview preview, Counts counts) {
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
                preview.collectionKey(), counts.documentCount(),
                counts.externalDocumentCount(),
                counts.localDocumentCount(),
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
        validateFrozenRequest(preview, request, false);
    }

    private void validateFrozenRequest(
            Preview preview, CollectionPurgeApplyRequest request, boolean asyncApply) {
        if ("ASYNC".equals(preview.executionMode()) && !asyncApply) {
            throw new RagException(
                    ErrorCode.COLLECTION_PURGE_ASYNC_PREVIEW_REQUIRES_ASYNC_APPLY,
                    "An async Collection purge preview requires async apply");
        }
        if (!"ASYNC".equals(preview.executionMode()) && asyncApply) {
            throw conflict("Async apply requires an async Collection purge preview");
        }
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
        cleanupExpiredOperations(limit);
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
                      AND NOT EXISTS (
                          SELECT 1 FROM rag_long_running_operations operation
                          WHERE operation.source_preview_id = rag_collection_purge_preview.id
                      )
                    ORDER BY result_expires_at
                    LIMIT ?
                )
                """, limit);
    }

    private void cleanupExpiredOperations(int limit) {
        jdbcTemplate.update("""
                WITH expired AS (
                    SELECT id FROM rag_long_running_operations
                    WHERE status IN ('SUCCEEDED', 'CANCELLED', 'STALE')
                      AND tombstone_expires_at <= clock_timestamp()
                    ORDER BY tombstone_expires_at LIMIT ?
                ), candidates AS (
                    SELECT target.ctid
                    FROM rag_collection_purge_operation_targets target
                    JOIN expired ON expired.id = target.operation_id
                    LIMIT ?
                )
                DELETE FROM rag_collection_purge_operation_targets target
                USING candidates WHERE target.ctid = candidates.ctid
                """, limit, limit);
        jdbcTemplate.update("""
                WITH expired AS (
                    SELECT id FROM rag_long_running_operations
                    WHERE status IN ('SUCCEEDED', 'CANCELLED', 'STALE')
                      AND tombstone_expires_at <= clock_timestamp()
                    ORDER BY tombstone_expires_at LIMIT ?
                ), candidates AS (
                    SELECT reference.ctid
                    FROM rag_long_running_operation_run_refs reference
                    JOIN expired ON expired.id = reference.operation_id
                    LIMIT ?
                )
                DELETE FROM rag_long_running_operation_run_refs reference
                USING candidates WHERE reference.ctid = candidates.ctid
                """, limit, limit);
        jdbcTemplate.update("""
                DELETE FROM rag_collection_purge_worker_slots slot
                USING rag_long_running_operations operation
                WHERE slot.operation_id = operation.id
                  AND operation.status IN ('SUCCEEDED', 'CANCELLED', 'STALE')
                  AND operation.tombstone_expires_at <= clock_timestamp()
                """);
        jdbcTemplate.update("""
                DELETE FROM rag_collection_operation_slot slot
                USING rag_long_running_operations operation
                WHERE slot.operation_id = operation.id
                  AND operation.status IN ('SUCCEEDED', 'CANCELLED', 'STALE')
                  AND operation.tombstone_expires_at <= clock_timestamp()
                """);
        jdbcTemplate.update("""
                DELETE FROM rag_long_running_operations operation
                WHERE operation.id IN (
                    SELECT candidate.id FROM rag_long_running_operations candidate
                    WHERE candidate.status IN ('SUCCEEDED', 'CANCELLED', 'STALE')
                      AND candidate.tombstone_expires_at <= clock_timestamp()
                      AND NOT EXISTS (
                          SELECT 1 FROM rag_collection_purge_operation_targets target
                          WHERE target.operation_id = candidate.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM rag_long_running_operation_run_refs reference
                          WHERE reference.operation_id = candidate.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM rag_collection_purge_worker_slots slot
                          WHERE slot.operation_id = candidate.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM rag_collection_operation_slot slot
                          WHERE slot.operation_id = candidate.id)
                    ORDER BY candidate.tombstone_expires_at LIMIT ?
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
                       confirmation_token_hash, fingerprint, status, execution_mode,
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
                        rs.getString("execution_mode"),
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
        return response(id, collection, plan.counts(), token, fingerprint,
                previewDeadline, operationDeadline);
    }

    private CollectionPurgePreviewResponse response(
            UUID id, RagCollection collection, Counts c, String token,
            String fingerprint, Instant previewDeadline, Instant operationDeadline) {
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
            String executionMode,
            Instant previewDeadline,
            Instant operationDeadline,
            String resultPayload) {
    }

    private record SessionRef(String owner, String session) {
    }

    private record PreviewAdmissionLease(
            String owner, String leaseOwner, UUID token, Instant admittedAt) {
    }

    private record ExistingPurgeOperation(UUID id, String fingerprint) {
    }

    private record AsyncWorkerFailure(String ownerPrincipalId, String status) {
    }

    private record AsyncWorkerOperation(
            UUID id,
            String ownerPrincipalId,
            long collectionId,
            UUID previewId,
            String phase,
            String manifestCursor,
            boolean cancelRequested,
            Instant fenceCompletedAt) {
    }

    private record MemoryDeleteBatch(int deletedRows, String cursor, boolean hasMoreInPage) {
    }

    private record AsyncPreviewScan(Counts counts, String fingerprint) {
    }

    private record CountSnapshot(
            long documentCount,
            long externalCount,
            long embeddingCount,
            long embeddingJobCount,
            long versionCount,
            long keywordChunkCount,
            long repairPreviewCount,
            long repairItemCount,
            long idempotencyCount,
            long feedbackCount,
            long feedbackReferenceCount,
            long documentAuditCount,
            long collectionAuditCount,
            long relocationCount,
            long sessionCount,
            long chatHistoryCount,
            long chatSummaryCount,
            long chatTurnCount,
            long embeddingStateCount,
            long localStateCount,
            long activeSyncCount,
            long activeRepairCount,
            long activeChatCount,
            long unindexedChatCount,
            long unindexedFeedbackCount) {
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
