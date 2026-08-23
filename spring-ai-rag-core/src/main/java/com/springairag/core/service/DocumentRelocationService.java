package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.dto.ExternalDocumentRelocateResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagDocumentLifecycleProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.security.ApiAccessPolicy;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DigestUtils;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 外部文档 Collection 投放位置的原子迁移协调器。 */
@Service
public class DocumentRelocationService {

    private static final String OPERATION_TYPE = "EXTERNAL_RELOCATE";
    private static final int RESPONSE_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RagDocumentRepository documentRepository;
    private final CollectionIdentityResolver collectionResolver;
    private final DocumentVersionService versionService;
    private final DocumentLifecycleService lifecycleService;
    private final RagDocumentLifecycleProperties properties;
    private final EntityManager entityManager;

    public DocumentRelocationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagDocumentRepository documentRepository,
            CollectionIdentityResolver collectionResolver,
            DocumentVersionService versionService,
            DocumentLifecycleService lifecycleService,
            RagProperties ragProperties,
            EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.collectionResolver = collectionResolver;
        this.versionService = versionService;
        this.lifecycleService = lifecycleService;
        this.properties = ragProperties.getDocumentLifecycle();
        this.entityManager = entityManager;
    }

    @Transactional
    public ExternalDocumentRelocateResponse relocate(
            ExternalDocumentRelocateRequest request,
            String rawIdempotencyKey) {
        if (!properties.isRelocationEnabled()) {
            throw new RagException(ErrorCode.DOCUMENT_RELOCATION_DISABLED,
                    "External document relocation is disabled");
        }
        NormalizedRequest normalized = normalize(request);
        Reservation reservation = reserve(rawIdempotencyKey, fingerprint(normalized));
        if (reservation.replayPayload() != null) {
            recheckReplayAcl(reservation.sourceCollectionId(), reservation.targetCollectionId());
            return readResponseEnvelope(reservation.replayPayload());
        }

        ApiAccessPolicy caller = ApiKeyCollectionAccess.currentPolicy();
        RagCollection source = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                normalized.sourceCollectionKey(), caller, collectionResolver);
        RagCollection target = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                normalized.targetCollectionKey(), caller, collectionResolver);
        if (Objects.equals(source.getId(), target.getId())) {
            throw new IllegalArgumentException(
                    "sourceCollectionKey and targetCollectionKey must be different");
        }
        ApiKeyCollectionAccess.requireCollectionId(source.getId(), caller);
        ApiKeyCollectionAccess.requireCollectionId(target.getId(), caller);

        List<RagCollection> orderedCollections = new ArrayList<>(List.of(source, target));
        orderedCollections.sort(Comparator.comparing(RagCollection::getId));
        expireAndRejectActiveRuns(source.getId(), target.getId(), normalized.sourceNamespace());

        List<CollectionIdentityResolver.ActiveCollectionToken> collectionTokens =
                orderedCollections.stream()
                        .map(c -> collectionResolver.beginActiveWrite(c.getId()))
                        .toList();

        Map<String, Object> documentRow = findDocument(
                source.getId(), normalized.sourceNamespace(), normalized.externalId());
        if (documentRow == null) {
            throw new RagException(ErrorCode.DOCUMENT_NOT_FOUND,
                    "External document was not found at the source address");
        }
        Long documentId = number(documentRow.get("id"));
        String sourceRevision = string(documentRow.get("source_revision"));
        if (string(documentRow.get("external_id")) == null) {
            throw new RagException(ErrorCode.DOCUMENT_NOT_EXTERNAL_MANAGED,
                    "Only externally managed documents can be relocated");
        }
        if (sourceRevision == null) {
            throw new RagException(ErrorCode.LEGACY_EXTERNAL_IDENTITY_REQUIRES_CLAIM,
                    "The legacy external identity must be claimed before relocation");
        }
        if (!sourceRevision.equals(normalized.expectedSourceRevision())) {
            throw new RagException(ErrorCode.DOCUMENT_REVISION_CONFLICT,
                    "expectedSourceRevision does not match the current source revision");
        }
        Map<Long, Long> sequences = new LinkedHashMap<>();
        for (RagCollection collection : orderedCollections) {
            sequences.put(collection.getId(), allocateSourceSequence(
                    collection.getId(), normalized.sourceNamespace()));
        }
        expireAndRejectActiveRuns(source.getId(), target.getId(), normalized.sourceNamespace());

        if (findDocument(target.getId(), normalized.sourceNamespace(),
                normalized.externalId()) != null) {
            throw new RagException(ErrorCode.TARGET_EXTERNAL_IDENTITY_EXISTS,
                    "The target external identity already exists");
        }
        Map<String, Object> targetMarker = findActiveMarker(
                target.getId(), normalized.sourceNamespace(), normalized.externalId());
        boolean reverse = targetMarker != null
                && Objects.equals(number(targetMarker.get("document_id")), documentId)
                && Objects.equals(number(targetMarker.get("target_collection_id")), source.getId());
        if (targetMarker != null && !reverse) {
            throw new RagException(ErrorCode.TARGET_EXTERNAL_IDENTITY_RETIRED,
                    "The target address is permanently retired by another relocation");
        }

        long expectedVersion = number(documentRow.get("version"));
        long expectedDocumentRevision = number(documentRow.get("document_revision"));
        Long updatedId = jdbcTemplate.query(
                """
                UPDATE rag_documents
                SET collection_id = ?,
                    source_mutation_sequence = ?,
                    last_seen_sync_run_id = NULL,
                    last_seen_sync_generation = NULL,
                    document_revision = document_revision + 1,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND collection_id = ?
                  AND source_namespace = ?
                  AND external_id = ?
                  AND source_revision = ?
                  AND version = ?
                  AND document_revision = ?
                RETURNING id
                """,
                ps -> {
                    ps.setLong(1, target.getId());
                    ps.setLong(2, sequences.get(target.getId()));
                    ps.setLong(3, documentId);
                    ps.setLong(4, source.getId());
                    ps.setString(5, normalized.sourceNamespace());
                    ps.setString(6, normalized.externalId());
                    ps.setString(7, sourceRevision);
                    ps.setLong(8, expectedVersion);
                    ps.setLong(9, expectedDocumentRevision);
                },
                (rs, rowNum) -> rs.getLong(1)).stream().findFirst().orElse(null);
        if (updatedId == null) {
            throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The document changed while relocation was being applied");
        }

        if (reverse) {
            int resolved = jdbcTemplate.update(
                    """
                    UPDATE rag_document_relocated_addresses
                    SET active = FALSE, resolved_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND active = TRUE
                    """,
                    number(targetMarker.get("id")));
            if (resolved != 1) {
                throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                        "The retired target address changed concurrently");
            }
        }
        jdbcTemplate.update(
                """
                UPDATE rag_document_relocated_addresses
                SET target_collection_id = ?
                WHERE document_id = ? AND active = TRUE
                """,
                target.getId(), documentId);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO rag_document_relocated_addresses (
                        source_collection_id, source_namespace, external_id,
                        document_id, target_collection_id,
                        relocation_idempotency_operation_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    source.getId(), normalized.sourceNamespace(), normalized.externalId(),
                    documentId, target.getId(), reservation.operationId());
        } catch (DataIntegrityViolationException e) {
            throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The source address was retired concurrently", e);
        }

        entityManager.clear();
        RagDocument relocated = documentRepository.findById(documentId)
                .orElseThrow(() -> new RagException(ErrorCode.DOCUMENT_NOT_FOUND,
                        "Relocated document disappeared"));
        RagDocumentVersion version = versionService.forceRecordVersion(
                relocated, "RELOCATE",
                "Collection relocated from " + source.getCollectionKey()
                        + " to " + target.getCollectionKey());

        for (CollectionIdentityResolver.ActiveCollectionToken token : collectionTokens) {
            collectionResolver.confirmActiveWrite(token);
        }

        ExternalDocumentRelocateResponse response = new ExternalDocumentRelocateResponse(
                relocated.getId(), source.getCollectionKey(), target.getCollectionKey(),
                relocated.getSourceNamespace(), relocated.getExternalId(),
                relocated.getSourceRevision(), "RELOCATED",
                relocated.getDocumentRevision(), version.getVersionNumber(), false,
                "PRESERVED", lifecycleService.read(relocated));
        complete(reservation, response, source.getId(), target.getId());
        return response;
    }

    private Reservation reserve(String rawKey, String fingerprint) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String key = rawKey.trim();
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 255 characters");
        }
        String owner = ChatPrincipal.fromCurrentRequest().id();
        String keyHash = DigestUtils.sha256(key);
        List<Long> inserted = jdbcTemplate.query(
                """
                INSERT INTO rag_document_idempotency_operations (
                    owner_principal_id, operation_type, idempotency_key_hash,
                    request_fingerprint, status, expires_at
                ) VALUES (?, ?, ?, ?, 'IN_PROGRESS',
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 hour'))
                ON CONFLICT (owner_principal_id, operation_type, idempotency_key_hash)
                DO NOTHING
                RETURNING id
                """,
                ps -> {
                    ps.setString(1, owner);
                    ps.setString(2, OPERATION_TYPE);
                    ps.setString(3, keyHash);
                    ps.setString(4, fingerprint);
                    ps.setInt(5, properties.getIdempotencyTtlHours());
                },
                (rs, rowNum) -> rs.getLong(1));
        if (!inserted.isEmpty()) {
            return new Reservation(inserted.getFirst(), owner, keyHash,
                    null, null, null);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, request_fingerprint, status, result_payload,
                       authorization_collection_ids[1] AS source_acl_id,
                       authorization_collection_ids[2] AS target_acl_id,
                       expires_at <= CURRENT_TIMESTAMP AS expired
                FROM rag_document_idempotency_operations
                WHERE owner_principal_id = ? AND operation_type = ?
                  AND idempotency_key_hash = ?
                """,
                owner, OPERATION_TYPE, keyHash);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Idempotency reservation disappeared");
        }
        Map<String, Object> row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("expired"))) {
            jdbcTemplate.update(
                    "DELETE FROM rag_document_idempotency_operations WHERE id = ? AND expires_at <= CURRENT_TIMESTAMP",
                    number(row.get("id")));
            return reserve(rawKey, fingerprint);
        }
        if (!fingerprint.equals(row.get("request_fingerprint"))) {
            throw new RagException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for another request");
        }
        if (!"SUCCEEDED".equals(row.get("status")) || row.get("result_payload") == null) {
            throw new RagException(ErrorCode.IDEMPOTENCY_OPERATION_IN_PROGRESS,
                    "An operation with this Idempotency-Key is still in progress");
        }
        return new Reservation(number(row.get("id")), owner, keyHash,
                String.valueOf(row.get("result_payload")),
                nullableNumber(row.get("source_acl_id")),
                nullableNumber(row.get("target_acl_id")));
    }

    private void complete(
            Reservation reservation,
            ExternalDocumentRelocateResponse response,
            long sourceCollectionId,
            long targetCollectionId) {
        List<Long> ids = List.of(sourceCollectionId, targetCollectionId).stream().sorted().toList();
        String payload = writeEnvelope(response);
        int updated = jdbcTemplate.update(
                """
                UPDATE rag_document_idempotency_operations
                SET status = 'SUCCEEDED', result_document_id = ?,
                    result_payload = ?::jsonb,
                    authorization_collection_ids = ARRAY[?, ?]::bigint[],
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'IN_PROGRESS'
                """,
                response.documentId(), payload, ids.get(0), ids.get(1),
                reservation.operationId());
        if (updated != 1) {
            throw new IllegalStateException("Cannot complete relocation idempotency record");
        }
    }

    private void recheckReplayAcl(Long first, Long second) {
        if (first == null || second == null) {
            throw new IllegalStateException("Relocation replay is missing authorization scope");
        }
        ApiAccessPolicy caller = ApiKeyCollectionAccess.currentPolicy();
        ApiKeyCollectionAccess.requireCollectionId(first, caller);
        ApiKeyCollectionAccess.requireCollectionId(second, caller);
    }

    private String writeEnvelope(ExternalDocumentRelocateResponse response) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "schemaVersion", RESPONSE_SCHEMA_VERSION,
                    "response", response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize relocation response", e);
        }
    }

    private ExternalDocumentRelocateResponse readResponseEnvelope(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.path("schemaVersion").asInt(-1) != RESPONSE_SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported relocation response schema");
            }
            return objectMapper.treeToValue(
                    root.required("response"), ExternalDocumentRelocateResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read relocation replay response", e);
        }
    }

    private void expireAndRejectActiveRuns(long sourceId, long targetId, String namespace) {
        jdbcTemplate.update(
                """
                UPDATE rag_document_sync_runs
                SET status = 'EXPIRED', completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE collection_id IN (?, ?) AND source_namespace = ?
                  AND status = 'ACTIVE' AND lease_expires_at <= CURRENT_TIMESTAMP
                """,
                sourceId, targetId, namespace);
        Long active = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM rag_document_sync_runs
                WHERE collection_id IN (?, ?) AND source_namespace = ?
                  AND status = 'ACTIVE' AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                Long.class, sourceId, targetId, namespace);
        if (active != null && active > 0) {
            throw new RagException(ErrorCode.ACTIVE_SYNC_RUN_CONFLICT,
                    "Relocation conflicts with an active source synchronization run");
        }
    }

    private long allocateSourceSequence(long collectionId, String namespace) {
        jdbcTemplate.update(
                """
                INSERT INTO rag_document_source_namespaces (collection_id, source_namespace)
                VALUES (?, ?) ON CONFLICT (collection_id, source_namespace) DO NOTHING
                """,
                collectionId, namespace);
        Long result = jdbcTemplate.queryForObject(
                """
                UPDATE rag_document_source_namespaces
                SET mutation_sequence = mutation_sequence + 1,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE collection_id = ? AND source_namespace = ?
                RETURNING mutation_sequence
                """,
                Long.class, collectionId, namespace);
        if (result == null) {
            throw new IllegalStateException("Cannot allocate source namespace sequence");
        }
        return result;
    }

    private Map<String, Object> findDocument(long collectionId, String namespace, String externalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, version, document_revision, external_id, source_revision
                FROM rag_documents
                WHERE collection_id = ? AND source_namespace = ? AND external_id = ?
                """,
                collectionId, namespace, externalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> findActiveMarker(long collectionId, String namespace, String externalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, document_id, target_collection_id
                FROM rag_document_relocated_addresses
                WHERE source_collection_id = ? AND source_namespace = ?
                  AND external_id = ? AND active = TRUE
                """,
                collectionId, namespace, externalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private NormalizedRequest normalize(ExternalDocumentRelocateRequest request) {
        String sourceKey = request.sourceCollectionKey().trim();
        String targetKey = request.targetCollectionKey().trim();
        String namespace = request.sourceNamespace().trim();
        String externalId = request.externalId().trim();
        if (sourceKey.equals(targetKey)) {
            throw new IllegalArgumentException(
                    "sourceCollectionKey and targetCollectionKey must be different");
        }
        requireVisibleAscii(namespace, 128, "sourceNamespace");
        requireVisibleAscii(externalId, 255, "externalId");
        return new NormalizedRequest(sourceKey, targetKey, namespace,
                externalId, request.expectedSourceRevision());
    }

    private String fingerprint(NormalizedRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourceCollectionKey", request.sourceCollectionKey());
        canonical.put("targetCollectionKey", request.targetCollectionKey());
        canonical.put("sourceNamespace", request.sourceNamespace());
        canonical.put("externalId", request.externalId());
        canonical.put("expectedSourceRevision", request.expectedSourceRevision());
        try {
            return DigestUtils.sha256(objectMapper.writeValueAsString(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint relocation request", e);
        }
    }

    private static void requireVisibleAscii(String value, int max, String field) {
        if (value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1-" + max + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20 || value.charAt(i) > 0x7e) {
                throw new IllegalArgumentException(field + " must contain visible ASCII only");
            }
        }
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Long nullableNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record NormalizedRequest(
            String sourceCollectionKey,
            String targetCollectionKey,
            String sourceNamespace,
            String externalId,
            String expectedSourceRevision) {
    }

    private record Reservation(
            long operationId,
            String owner,
            String keyHash,
            String replayPayload,
            Long sourceCollectionId,
            Long targetCollectionId) {
    }
}
