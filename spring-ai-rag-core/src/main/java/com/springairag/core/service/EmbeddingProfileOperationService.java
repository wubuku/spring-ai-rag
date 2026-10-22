package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionEmbeddingProfileOperationRequest;
import com.springairag.api.dto.LongRunningOperationResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.IdempotencyKeyValidator;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileRegistry;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.exception.LongRunningOperationCapacityException;
import com.springairag.core.security.ApiKeyCollectionAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 创建跨文档 Profile 切换 Operation。 */
@Service
public class EmbeddingProfileOperationService {

    private static final int OWNER_ACTIVE_LIMIT = 4;
    private static final int GLOBAL_ACTIVE_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingProfileRegistry profileRegistry;
    private final CollectionIdentityResolver identityResolver;
    private final LongRunningOperationService operationService;
    private final TransactionTemplate transactionTemplate;

    public EmbeddingProfileOperationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EmbeddingProfileRegistry profileRegistry,
            CollectionIdentityResolver identityResolver,
            LongRunningOperationService operationService,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.profileRegistry = profileRegistry;
        this.identityResolver = identityResolver;
        this.operationService = operationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public LongRunningOperationResponse create(
            CollectionEmbeddingProfileOperationRequest requestBody,
            List<String> idempotencyHeader,
            HttpServletRequest request) {
        String idempotencyKey = IdempotencyKeyValidator.normalize(idempotencyHeader);
        if (idempotencyKey == null) {
            throw new RagException(
                    ErrorCode.MISSING_HEADER,
                    "Idempotency-Key is required for an Embedding Profile operation");
        }
        RagCollection collection = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                requestBody.collectionKey(),
                ApiKeyCollectionAccess.currentPolicy(request),
                identityResolver);
        long version = collection.getVersion() == null ? 0 : collection.getVersion();
        if (version != requestBody.expectedCollectionVersion()) {
            throw new RagException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Collection version changed before Profile operation admission");
        }
        EmbeddingProfile source = profileRegistry.getProfileForCollection(collection.getId());
        EmbeddingProfile target;
        try {
            target = profileRegistry.findRequiredByKey(requestBody.targetProfileKey());
        } catch (IllegalStateException exception) {
            throw new RagException(ErrorCode.BAD_REQUEST,
                    "Target Embedding Profile is not registered or enabled");
        }
        String owner = ChatPrincipal.from(request).id();
        String idempotencyHash = IdempotencyKeyValidator.hash(idempotencyKey);
        String fingerprint = fingerprint(collection, target, version);
        UUID operationId = transactionTemplate.execute(status -> createTransaction(
                requestBody, collection, source, target, owner,
                idempotencyHash, fingerprint, version));
        if (operationId == null) {
            throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Embedding Profile operation was not admitted");
        }
        return operationService.get(operationId, request);
    }

    private UUID createTransaction(
            CollectionEmbeddingProfileOperationRequest requestBody,
            RagCollection collection,
            EmbeddingProfile source,
            EmbeddingProfile target,
            String owner,
            String idempotencyHash,
            String fingerprint,
            long version) {
        List<ExistingOperation> existing = jdbcTemplate.query("""
                SELECT id, request_fingerprint_sha256
                FROM rag_long_running_operations
                WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                  AND operation_kind = 'EMBEDDING_PROFILE_SWITCH'
                  AND collection_id = ?
                """,
                (rs, rowNum) -> new ExistingOperation(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_fingerprint_sha256")),
                owner, idempotencyHash, collection.getId());
        if (!existing.isEmpty()) {
            ExistingOperation replay = existing.getFirst();
            if (!constantEquals(replay.fingerprint(), fingerprint)) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was reused with a different Profile operation");
            }
            return replay.id();
        }
        if (source.id() == target.id()) {
            throw new RagException(
                    ErrorCode.LONG_RUNNING_OPERATION_STATE_CONFLICT,
                    "Collection already uses the requested Embedding Profile");
        }

        UUID operationId = UUID.randomUUID();
        String progress = progress(source, target, version,
                collection.getChatCommitFenceVersion() == null
                        ? 0L : collection.getChatCommitFenceVersion());
        List<UUID> inserted = jdbcTemplate.query("""
                INSERT INTO rag_long_running_operations (
                    id, owner_principal_id, resource_type, collection_id,
                    operation_kind, idempotency_key_sha256,
                    request_fingerprint_sha256, status, progress_payload,
                    target_embedding_profile_id, total_count
                ) VALUES (?, ?, 'COLLECTION', ?, 'EMBEDDING_PROFILE_SWITCH',
                          ?, ?, 'QUEUED', ?::jsonb, ?,
                          (SELECT COUNT(*) FROM rag_documents
                           WHERE collection_id = ? AND enabled = TRUE))
                ON CONFLICT (owner_principal_id, idempotency_key_sha256,
                             operation_kind, collection_id) DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                operationId, owner, collection.getId(), idempotencyHash,
                fingerprint, progress, target.id(), collection.getId());
        if (inserted.isEmpty()) {
            ExistingOperation replay = jdbcTemplate.queryForObject("""
                    SELECT id, request_fingerprint_sha256
                    FROM rag_long_running_operations
                    WHERE owner_principal_id = ? AND idempotency_key_sha256 = ?
                      AND operation_kind = 'EMBEDDING_PROFILE_SWITCH'
                      AND collection_id = ?
                    """,
                    (rs, rowNum) -> new ExistingOperation(
                            rs.getObject("id", UUID.class),
                            rs.getString("request_fingerprint_sha256")),
                    owner, idempotencyHash, collection.getId());
            if (replay == null || !constantEquals(replay.fingerprint(), fingerprint)) {
                throw new RagException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was reused with a different Profile operation");
            }
            return replay.id();
        }

        jdbcTemplate.update("""
                INSERT INTO rag_long_running_operation_owner_capacity (
                    owner_principal_id, active_count)
                VALUES (?, 0)
                ON CONFLICT (owner_principal_id) DO NOTHING
                """, owner);
        int ownerReserved = jdbcTemplate.update("""
                UPDATE rag_long_running_operation_owner_capacity
                SET active_count = active_count + 1, updated_at = clock_timestamp()
                WHERE owner_principal_id = ? AND active_count < ?
                """, owner, OWNER_ACTIVE_LIMIT);
        if (ownerReserved != 1) {
            throw new LongRunningOperationCapacityException(
                    "Owner operation capacity is exhausted");
        }
        int globalReserved = jdbcTemplate.update("""
                UPDATE rag_long_running_operation_global_capacity
                SET active_count = active_count + 1, updated_at = clock_timestamp()
                WHERE singleton_id = 1 AND active_count < ?
                """, GLOBAL_ACTIVE_LIMIT);
        if (globalReserved != 1) {
            throw new LongRunningOperationCapacityException(
                    "Global operation capacity is exhausted");
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO rag_collection_operation_slot (
                        collection_id, holder_kind, operation_id)
                    VALUES (?, 'PROFILE_SWITCH', ?)
                    """, collection.getId(), operationId);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new RagException(
                    ErrorCode.COLLECTION_OPERATION_IN_PROGRESS,
                    "Another durable operation is active for this Collection");
        }
        int current = jdbcTemplate.update("""
                UPDATE rag_collection
                SET updated_at = updated_at
                WHERE id = ? AND version = ?
                  AND active_embedding_profile_id = ?
                  AND deleted = FALSE AND purged_at IS NULL
                """, collection.getId(), version, source.id());
        if (current != 1) {
            throw new RagException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Collection changed while admitting the Profile operation");
        }
        return operationId;
    }

    private String progress(
            EmbeddingProfile source,
            EmbeddingProfile target,
            long expectedVersion,
            long expectedChatFence) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "sourceProfileId", source.id(),
                    "sourceProfileKey", source.profileKey(),
                    "targetProfileId", target.id(),
                    "targetProfileKey", target.profileKey(),
                    "expectedCollectionVersion", expectedVersion,
                    "expectedChatCommitFenceVersion", expectedChatFence,
                    "phase", "SCAN"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode Profile operation snapshot", exception);
        }
    }

    private static String fingerprint(
            RagCollection collection,
            EmbeddingProfile target,
            long version) {
        String material = collection.getId() + "\u0000"
                + target.id() + "\u0000" + version;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private record ExistingOperation(UUID id, String fingerprint) {
    }
}
