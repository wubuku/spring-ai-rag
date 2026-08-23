package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DerivationRepairApplyRequest;
import com.springairag.api.dto.DerivationRepairPreviewRequest;
import com.springairag.api.dto.DerivationRepairPreviewResponse;
import com.springairag.api.dto.DerivationRepairStatusResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagDocumentLifecycleProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** preview-first、有限批量、可恢复的派生修复协调器。 */
@Service
public class DerivationRepairService {

    private static final int MAX_DOCUMENTS = 100;
    private static final int CLEANUP_BATCH_SIZE = 1_000;
    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            "READY", "KEYWORD_ONLY", "INDEXING", "LOCAL_UNAVAILABLE",
            "NOT_REQUESTED", "CORRUPT", "DISABLED");
    private static final Set<String> ALLOWED_VECTOR_CONDITIONS = Set.of(
            "READY", "CORRUPT", "INDEXING", "NOT_REQUESTED", "FAILED", "STALE");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DerivationIntegrityRepository integrityRepository;
    private final DerivationIntegrityService integrityService;
    private final RagDocumentRepository documentRepository;
    private final KeywordIndexPersistenceService keywordIndexService;
    private final EmbeddingDispatchService dispatchService;
    private final CollectionIdentityResolver collectionResolver;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingProfileProvider profileProvider;
    private final RagDocumentLifecycleProperties properties;

    public DerivationRepairService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            DerivationIntegrityRepository integrityRepository,
            DerivationIntegrityService integrityService,
            RagDocumentRepository documentRepository,
            KeywordIndexPersistenceService keywordIndexService,
            EmbeddingDispatchService dispatchService,
            CollectionIdentityResolver collectionResolver,
            EmbeddingProfileProvider profileProvider,
            RagProperties ragProperties,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.integrityRepository = integrityRepository;
        this.integrityService = integrityService;
        this.documentRepository = documentRepository;
        this.keywordIndexService = keywordIndexService;
        this.dispatchService = dispatchService;
        this.collectionResolver = collectionResolver;
        this.profileProvider = profileProvider;
        this.properties = ragProperties.getDocumentLifecycle();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public DerivationRepairPreviewResponse preview(DerivationRepairPreviewRequest request) {
        requireEnabled();
        cleanupExpiredResults();
        int max = request.maxDocuments() <= 0 ? MAX_DOCUMENTS
                : Math.min(request.maxDocuments(), MAX_DOCUMENTS);
        RagCollection collection = integrityService.requireCollection(request.collectionKey());
        long profileId = profileId();
        Set<String> buckets = upperSet(request.buckets());
        Set<String> vectorConditions = upperSet(request.vectorConditions());
        validateSelection(buckets, vectorConditions);
        long selected = integrityRepository.countRepairSelection(
                collection.getId(), buckets, vectorConditions);
        List<DerivationIntegrityRepository.Snapshot> candidates = integrityRepository
                .scanRepairCandidates(collection.getId(), buckets, vectorConditions, max);

        UUID repairId = UUID.randomUUID();
        String token = newToken();
        String tokenHash = DigestUtils.sha256(token);
        List<PlanItem> plan = candidates.stream().map(this::plan)
                .filter(Objects::nonNull).toList();
        requireActiveProfile(profileId);
        String fingerprint = fingerprint(collection.getId(), profileId, plan);
        Instant now = Instant.now();
        Instant previewDeadline = now.plusSeconds(15 * 60L);
        Instant operationDeadline = now.plusSeconds(60 * 60L);
        Instant resultExpiresAt = now.plusSeconds(24 * 60 * 60L);
        jdbcTemplate.update(
                """
                INSERT INTO rag_derivation_repair_previews (
                    id, owner_principal_id, collection_id, active_embedding_profile_id,
                    preview_token_hash, preview_fingerprint, request_payload,
                    plan_payload, status, preview_deadline, operation_deadline,
                    result_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'PREVIEWED', ?, ?, ?)
                """,
                repairId, ChatPrincipal.fromCurrentRequest().id(), collection.getId(), profileId,
                tokenHash, fingerprint, json(Map.of(
                        "buckets", buckets, "vectorConditions", vectorConditions,
                        "maxDocuments", max)), json(plan),
                Timestamp.from(previewDeadline), Timestamp.from(operationDeadline),
                Timestamp.from(resultExpiresAt));
        for (PlanItem item : plan) {
            jdbcTemplate.update(
                    """
                    INSERT INTO rag_derivation_repair_items (
                        repair_id, document_id, planned_document_revision,
                        planned_document_version, planned_content_hash,
                        planned_local_generation, planned_vector_generation,
                        action, reason_code, status, local_action_status,
                        vector_action_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?)
                    """,
                    repairId, item.documentId(), item.documentRevision(),
                    item.documentVersion(), item.contentHash(), item.localGeneration(),
                    item.vectorGeneration(), item.action(), item.reasonCode(),
                    item.localAction() ? "PLANNED" : "NOT_PLANNED",
                    item.vectorAction() ? "PLANNED" : "NOT_PLANNED");
        }
        Map<String, Long> counts = plan.stream().collect(Collectors.groupingBy(
                PlanItem::action, LinkedHashMap::new, Collectors.counting()));
        return new DerivationRepairPreviewResponse(
                repairId, collection.getCollectionKey(), fingerprint, token,
                previewDeadline,
                plan.stream().map(item -> new DerivationRepairPreviewResponse.Item(
                        item.documentId(), item.action(), item.reasonCode())).toList(),
                counts, Math.max(0, selected - plan.size()));
    }

    public DerivationRepairStatusResponse apply(DerivationRepairApplyRequest request) {
        requireEnabled();
        PreviewRow preview = requirePreview(request.repairId());
        requireCollectionAccess(preview.collectionId(), request.collectionKey());
        if (!DigestUtils.sha256(request.previewToken()).equals(preview.tokenHash())
                || !request.previewFingerprint().equals(preview.fingerprint())) {
            throw new RagException(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                    "Preview token or fingerprint does not match");
        }
        if ("COMPLETED".equals(preview.status())) {
            return status(request.repairId());
        }
        requireActiveProfile(preview.profileId());
        if (preview.previewDeadline().isBefore(Instant.now())
                && "PREVIEWED".equals(preview.status())) {
            expire(preview.id());
            throw new RagException(ErrorCode.DERIVATION_REPAIR_EXPIRED,
                    "Derivation repair preview expired");
        }
        String leaseHash = DigestUtils.sha256(newToken());
        int claimed = jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_previews
                SET status = 'APPLYING', apply_lease_owner_hash = ?,
                    apply_lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '2 minutes'
                WHERE id = ? AND (
                    status = 'PREVIEWED'
                    OR (status = 'APPLYING' AND apply_lease_expires_at <= CURRENT_TIMESTAMP)
                ) AND operation_deadline > CURRENT_TIMESTAMP
                """,
                leaseHash, preview.id());
        if (claimed == 0) {
            if (!preview.operationDeadline().isAfter(Instant.now())) {
                expireOperation(preview.id());
                throw new RagException(ErrorCode.DERIVATION_REPAIR_EXPIRED,
                        "Derivation repair operation expired");
            }
            return status(preview.id());
        }

        List<Long> itemIds = jdbcTemplate.queryForList(
                """
                SELECT document_id FROM rag_derivation_repair_items
                WHERE repair_id = ? AND status IN ('PLANNED', 'APPLYING')
                ORDER BY document_id
                """,
                Long.class, preview.id());
        for (Long documentId : itemIds) {
            try {
                Boolean itemClaimed = transactionTemplate.execute(status ->
                        claimItem(preview.id(), documentId, leaseHash));
                if (!Boolean.TRUE.equals(itemClaimed)) {
                    continue;
                }
                Boolean localReady = transactionTemplate.execute(status ->
                        applyLocalPhase(preview, documentId, leaseHash));
                if (!Boolean.TRUE.equals(localReady)) {
                    continue;
                }
                PhaseResult phaseResult = transactionTemplate.execute(status ->
                        applyVectorPhase(preview, documentId, leaseHash));
                if (phaseResult == null) {
                    continue;
                }
                transactionTemplate.executeWithoutResult(status ->
                        finishSucceeded(preview.id(), documentId, leaseHash, phaseResult));
            } catch (RuntimeException e) {
                failItem(preview.id(), documentId, leaseHash, safeError(e));
            }
        }
        jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_previews preview
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                    result_expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours',
                    apply_lease_owner_hash = NULL, apply_lease_expires_at = NULL
                WHERE id = ? AND status = 'APPLYING'
                  AND apply_lease_owner_hash = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM rag_derivation_repair_items item
                    WHERE item.repair_id = preview.id
                      AND item.status IN ('PLANNED', 'APPLYING')
                  )
                """,
                preview.id(), leaseHash);
        return status(preview.id());
    }

    public DerivationRepairStatusResponse status(UUID repairId) {
        PreviewRow preview = requirePreview(repairId);
        RagCollection collection = collectionResolver.requireIncludingDeleted(
                preview.collectionId(), null);
        ApiKeyCollectionAccess.requireCollectionId(
                preview.collectionId(), ApiKeyCollectionAccess.currentPolicy());
        List<DerivationRepairStatusResponse.Item> items = jdbcTemplate.query(
                """
                SELECT document_id, action, status, local_action_status,
                       vector_action_status, embedding_job_id, result_code, error_message
                FROM rag_derivation_repair_items
                WHERE repair_id = ? ORDER BY document_id
                """,
                (rs, rowNum) -> new DerivationRepairStatusResponse.Item(
                        rs.getLong("document_id"), rs.getString("action"),
                        rs.getString("status"), rs.getString("local_action_status"),
                        rs.getString("vector_action_status"),
                        rs.getObject("embedding_job_id", UUID.class),
                        rs.getString("result_code"), rs.getString("error_message")),
                repairId);
        return new DerivationRepairStatusResponse(
                repairId, collection.getCollectionKey(), preview.status(),
                preview.createdAt(), preview.completedAt(), items);
    }

    private boolean claimItem(UUID repairId, long documentId, String leaseHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                UPDATE rag_derivation_repair_items item
                SET status = 'APPLYING', lease_owner_hash = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                    attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE item.repair_id = ? AND item.document_id = ?
                  AND item.attempt_count < 10
                  AND (item.status = 'PLANNED'
                    OR (item.status = 'APPLYING'
                      AND item.lease_expires_at <= CURRENT_TIMESTAMP))
                  AND EXISTS (
                    SELECT 1 FROM rag_derivation_repair_previews preview
                    WHERE preview.id = item.repair_id
                      AND preview.status = 'APPLYING'
                      AND preview.apply_lease_owner_hash = ?
                      AND preview.apply_lease_expires_at > CURRENT_TIMESTAMP
                  )
                RETURNING *
                """,
                leaseHash, repairId, documentId, leaseHash);
        return !rows.isEmpty();
    }

    private boolean applyLocalPhase(
            PreviewRow preview, long documentId, String leaseHash) {
        if (!lockItemLease(preview.id(), documentId, leaseHash)) {
            return false;
        }
        Map<String, Object> item = requireItem(preview.id(), documentId);
        RagDocument document = documentRepository.findById(documentId).orElse(null);
        String localAction = String.valueOf(item.get("local_action_status"));
        boolean postLocal = "SUCCEEDED".equals(localAction);
        if (!matchesDocument(preview, document, item, postLocal)) {
            finishSkipped(preview.id(), documentId, leaseHash, "SKIPPED_CHANGED");
            return false;
        }
        if (!"PLANNED".equals(localAction)) {
            return true;
        }
        DerivationIntegrityRepository.Snapshot beforeLocal = integrityRepository.inspect(document);
        if (beforeLocal.localGeneration() != number(item.get("planned_local_generation"))) {
            finishSkipped(preview.id(), documentId, leaseHash, "SKIPPED_CHANGED");
            return false;
        }
        CollectionIdentityResolver.ActiveCollectionToken token =
                collectionResolver.beginActiveWrite(preview.collectionId());
        jdbcTemplate.update(
                "UPDATE rag_derivation_repair_items SET local_action_status = 'APPLYING' "
                        + "WHERE repair_id = ? AND document_id = ? AND lease_owner_hash = ?",
                preview.id(), documentId, leaseHash);
        keywordIndexService.ensureCurrent(document);
        DerivationIntegrityRepository.Snapshot afterLocal = integrityRepository.inspect(document);
        if (!afterLocal.localFresh()) {
            throw new IllegalStateException("Local derivation did not become current");
        }
        jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_items
                SET local_action_status = 'SUCCEEDED',
                    post_local_document_version = ?, post_local_content_hash = ?,
                    post_local_generation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND document_id = ? AND lease_owner_hash = ?
                """,
                document.getVersion(), document.getContentHash(), afterLocal.localGeneration(),
                preview.id(), documentId, leaseHash);
        collectionResolver.confirmActiveWrite(token);
        return true;
    }

    private PhaseResult applyVectorPhase(
            PreviewRow preview, long documentId, String leaseHash) {
        requireActiveProfile(preview.profileId());
        if (!lockItemLease(preview.id(), documentId, leaseHash)) {
            return null;
        }
        Map<String, Object> item = requireItem(preview.id(), documentId);
        RagDocument document = documentRepository.findById(documentId).orElse(null);
        boolean postLocal = "SUCCEEDED".equals(String.valueOf(item.get("local_action_status")));
        if (!matchesDocument(preview, document, item, postLocal)) {
            finishSkipped(preview.id(), documentId, leaseHash, "SKIPPED_CHANGED");
            return null;
        }
        DerivationIntegrityRepository.Snapshot current = integrityRepository.inspect(document);
        if (postLocal && current.localGeneration()
                != nullableNumber(item.get("post_local_generation"), -1)) {
            finishSkipped(preview.id(), documentId, leaseHash, "SKIPPED_CHANGED");
            return null;
        }
        if (!current.localFresh()) {
            throw new IllegalStateException("Vector repair requires a current local derivation");
        }
        if (current.vectorGeneration() != number(item.get("planned_vector_generation"))) {
            finishSkipped(preview.id(), documentId, leaseHash, "SKIPPED_CHANGED");
            return null;
        }
        String vectorAction = String.valueOf(item.get("vector_action_status"));
        UUID jobId = null;
        String resultCode = "REBUILT_LOCAL";
        if ("PLANNED".equals(vectorAction)) {
            CollectionIdentityResolver.ActiveCollectionToken token =
                    collectionResolver.beginActiveWrite(preview.collectionId());
            if (current.vectorFresh()) {
                jdbcTemplate.update(
                        "UPDATE rag_derivation_repair_items SET vector_action_status = 'SKIPPED' "
                                + "WHERE repair_id = ? AND document_id = ? AND lease_owner_hash = ?",
                        preview.id(), documentId, leaseHash);
                resultCode = "ALREADY_FRESH";
            } else if ("INDEXING".equals(current.vectorCondition())) {
                jdbcTemplate.update(
                        "UPDATE rag_derivation_repair_items SET vector_action_status = 'SKIPPED' "
                                + "WHERE repair_id = ? AND document_id = ? AND lease_owner_hash = ?",
                        preview.id(), documentId, leaseHash);
                resultCode = "NOOP_ALREADY_CONVERGING";
                jobId = current.activeJobId();
            } else {
                jdbcTemplate.update(
                        "UPDATE rag_derivation_repair_items SET vector_action_status = 'APPLYING' "
                                + "WHERE repair_id = ? AND document_id = ? AND lease_owner_hash = ?",
                        preview.id(), documentId, leaseHash);
                EmbeddingDispatchService.Result queued = dispatchService.enqueueInCurrentTransaction(
                        document, false, true, "DERIVATION_REPAIR");
                requireActiveProfile(preview.profileId());
                jobId = queued.embeddingJobId();
                jdbcTemplate.update(
                        "UPDATE rag_derivation_repair_items SET vector_action_status = 'SUCCEEDED', embedding_job_id = ? "
                                + "WHERE repair_id = ? AND document_id = ? AND lease_owner_hash = ?",
                        jobId, preview.id(), documentId, leaseHash);
                resultCode = "QUEUED_VECTOR";
            }
            collectionResolver.confirmActiveWrite(token);
        }
        return new PhaseResult(resultCode, jobId);
    }

    private void finishSucceeded(
            UUID repairId, long documentId, String leaseHash, PhaseResult result) {
        jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_items
                SET status = 'SUCCEEDED', result_code = ?, embedding_job_id = COALESCE(?, embedding_job_id),
                    lease_owner_hash = NULL, lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND document_id = ?
                  AND status = 'APPLYING' AND lease_owner_hash = ?
                """,
                result.resultCode(), result.jobId(), repairId, documentId, leaseHash);
    }

    private Map<String, Object> requireItem(UUID repairId, long documentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM rag_derivation_repair_items WHERE repair_id = ? AND document_id = ?",
                repairId, documentId);
        if (rows.isEmpty()) {
            throw new RagException(ErrorCode.NOT_FOUND, "Derivation repair item was not found");
        }
        return rows.getFirst();
    }

    private boolean matchesDocument(
            PreviewRow preview,
            RagDocument document,
            Map<String, Object> item,
            boolean postLocal) {
        if (document == null
                || !Objects.equals(document.getCollectionId(), preview.collectionId())
                || !Boolean.TRUE.equals(document.getEnabled())
                || document.getSourceDeletedAt() != null
                || document.getDocumentRevision()
                    != number(item.get("planned_document_revision"))) {
            return false;
        }
        long expectedVersion = postLocal
                ? nullableNumber(item.get("post_local_document_version"),
                    number(item.get("planned_document_version")))
                : number(item.get("planned_document_version"));
        Object expectedHash = postLocal && item.get("post_local_content_hash") != null
                ? item.get("post_local_content_hash") : item.get("planned_content_hash");
        return document.getVersion() == expectedVersion
                && Objects.equals(document.getContentHash(), expectedHash);
    }

    private void failItem(
            UUID repairId, long documentId, String leaseHash, String message) {
        jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_items
                SET status = 'FAILED',
                    local_action_status = CASE
                        WHEN local_action_status IN ('PLANNED', 'APPLYING') THEN 'FAILED'
                        ELSE local_action_status END,
                    vector_action_status = CASE
                        WHEN vector_action_status IN ('PLANNED', 'APPLYING')
                          THEN CASE WHEN local_action_status IN ('SUCCEEDED', 'NOT_PLANNED')
                               THEN 'FAILED' ELSE 'SKIPPED' END
                        ELSE vector_action_status END,
                    result_code = 'REPAIR_ACTION_FAILED', error_message = ?,
                    lease_owner_hash = NULL, lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND document_id = ?
                  AND status = 'APPLYING' AND lease_owner_hash = ?
                """,
                message, repairId, documentId, leaseHash);
    }

    private void finishSkipped(
            UUID repairId, long documentId, String leaseHash, String resultCode) {
        jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_items
                SET status = 'SKIPPED', result_code = ?,
                    local_action_status = CASE WHEN local_action_status IN ('PLANNED', 'APPLYING')
                        THEN 'SKIPPED' ELSE local_action_status END,
                    vector_action_status = CASE WHEN vector_action_status IN ('PLANNED', 'APPLYING')
                        THEN 'SKIPPED' ELSE vector_action_status END,
                    lease_owner_hash = NULL, lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND document_id = ?
                  AND status = 'APPLYING' AND lease_owner_hash = ?
                """,
                resultCode, repairId, documentId, leaseHash);
    }

    private boolean lockItemLease(UUID repairId, long documentId, String leaseHash) {
        return jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_items
                SET updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND document_id = ?
                  AND status = 'APPLYING' AND lease_owner_hash = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                repairId, documentId, leaseHash) == 1;
    }

    private PreviewRow requirePreview(UUID repairId) {
        String owner = ChatPrincipal.fromCurrentRequest().id();
        List<PreviewRow> rows = jdbcTemplate.query(
                """
                SELECT id, owner_principal_id, collection_id,
                       active_embedding_profile_id, preview_token_hash,
                       preview_fingerprint, status, preview_deadline,
                       operation_deadline, created_at, completed_at
                FROM rag_derivation_repair_previews
                WHERE id = ? AND owner_principal_id = ?
                """,
                (rs, rowNum) -> new PreviewRow(
                        rs.getObject("id", UUID.class), rs.getString("owner_principal_id"),
                        rs.getLong("collection_id"),
                        rs.getLong("active_embedding_profile_id"),
                        rs.getString("preview_token_hash"),
                        rs.getString("preview_fingerprint"), rs.getString("status"),
                        rs.getTimestamp("preview_deadline").toInstant(),
                        rs.getTimestamp("operation_deadline").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null
                                : rs.getTimestamp("completed_at").toInstant()),
                repairId, owner);
        if (rows.isEmpty()) {
            throw new RagException(ErrorCode.NOT_FOUND, "Derivation repair was not found");
        }
        return rows.getFirst();
    }

    private void requireCollectionAccess(long collectionId, String collectionKey) {
        ApiAccessPolicy caller = ApiKeyCollectionAccess.currentPolicy();
        RagCollection collection = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                collectionKey, caller, collectionResolver);
        if (!Objects.equals(collection.getId(), collectionId)) {
            throw new RagException(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                    "Repair Collection does not match the preview");
        }
    }

    private void expire(UUID id) {
        jdbcTemplate.update(
                """
                UPDATE rag_derivation_repair_previews
                SET status = 'EXPIRED', completed_at = CURRENT_TIMESTAMP,
                    result_expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                WHERE id = ? AND status = 'PREVIEWED'
                """,
                id);
    }

    private void expireOperation(UUID id) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    UPDATE rag_derivation_repair_items
                    SET status = 'FAILED',
                        local_action_status = CASE
                            WHEN local_action_status IN ('PLANNED', 'APPLYING') THEN 'FAILED'
                            ELSE local_action_status END,
                        vector_action_status = CASE
                            WHEN vector_action_status IN ('PLANNED', 'APPLYING') THEN 'SKIPPED'
                            ELSE vector_action_status END,
                        result_code = 'OPERATION_EXPIRED',
                        lease_owner_hash = NULL, lease_expires_at = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE repair_id = ? AND status IN ('PLANNED', 'APPLYING')
                    """,
                    id);
            jdbcTemplate.update(
                    """
                    UPDATE rag_derivation_repair_previews
                    SET status = 'EXPIRED', completed_at = CURRENT_TIMESTAMP,
                        result_expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours',
                        apply_lease_owner_hash = NULL, apply_lease_expires_at = NULL
                    WHERE id = ? AND status IN ('PREVIEWED', 'APPLYING')
                    """,
                    id);
        });
    }

    private PlanItem plan(DerivationIntegrityRepository.Snapshot snapshot) {
        if (!snapshot.toResponse().repairable()) {
            return null;
        }
        boolean local = !snapshot.localFresh();
        boolean vector = !snapshot.vectorFresh()
                && !"INDEXING".equals(snapshot.vectorCondition());
        String action = local && vector ? "REBUILD_LOCAL_AND_QUEUE_VECTOR"
                : local ? "REBUILD_LOCAL" : "QUEUE_VECTOR";
        return new PlanItem(snapshot.documentId(), snapshot.documentRevision(),
                snapshot.documentVersion(), snapshot.contentHash(), snapshot.localGeneration(),
                snapshot.vectorGeneration(), local, vector, action, snapshot.reasonCode());
    }

    private void cleanupExpiredResults() {
        jdbcTemplate.update(
                """
                DELETE FROM rag_derivation_repair_previews
                WHERE id IN (
                    SELECT id FROM rag_derivation_repair_previews
                    WHERE status IN ('COMPLETED', 'EXPIRED')
                      AND result_expires_at <= CURRENT_TIMESTAMP
                    ORDER BY result_expires_at, id
                    LIMIT ?
                )
                """,
                CLEANUP_BATCH_SIZE);
    }

    private static void validateSelection(
            Set<String> buckets, Set<String> vectorConditions) {
        if (buckets.isEmpty()) {
            throw new IllegalArgumentException("buckets must contain at least one value");
        }
        if (!ALLOWED_BUCKETS.containsAll(buckets)) {
            throw new IllegalArgumentException("buckets contains an unsupported value");
        }
        if (!ALLOWED_VECTOR_CONDITIONS.containsAll(vectorConditions)) {
            throw new IllegalArgumentException(
                    "vectorConditions contains an unsupported value");
        }
    }

    private String fingerprint(long collectionId, long profileId, List<PlanItem> plan) {
        return DigestUtils.sha256(json(Map.of(
                "collectionId", collectionId,
                "activeEmbeddingProfileId", profileId,
                "items", plan)));
    }

    private void requireActiveProfile(long expectedProfileId) {
        if (profileId() != expectedProfileId) {
            throw new RagException(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                    "Active embedding Profile changed after repair preview");
        }
    }

    private long profileId() {
        return profileProvider.getActiveProfile().id();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize derivation repair plan", e);
        }
    }

    private static Set<String> upperSet(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static long nullableNumber(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String safeError(RuntimeException error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            value = error.getClass().getSimpleName();
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private void requireEnabled() {
        if (!properties.isDerivationRepairEnabled()) {
            throw new RagException(ErrorCode.DERIVATION_REPAIR_DISABLED,
                    "Derivation repair is disabled");
        }
    }

    private record PlanItem(
            long documentId,
            long documentRevision,
            long documentVersion,
            String contentHash,
            long localGeneration,
            long vectorGeneration,
            boolean localAction,
            boolean vectorAction,
            String action,
            String reasonCode) {
    }

    private record PhaseResult(String resultCode, UUID jobId) {
    }

    private record PreviewRow(
            UUID id,
            String owner,
            long collectionId,
            long profileId,
            String tokenHash,
            String fingerprint,
            String status,
            Instant previewDeadline,
            Instant operationDeadline,
            Instant createdAt,
            Instant completedAt) {
    }
}
