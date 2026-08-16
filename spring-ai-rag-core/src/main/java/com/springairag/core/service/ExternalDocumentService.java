package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentBatchUpsertResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.api.dto.DocumentDetailResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DigestUtils;
import com.springairag.core.util.DocumentMapper;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stable-identity synchronization service for ordinary external documents.
 *
 * <p>Persistence and embedding are deliberately separated: identity/CAS/version
 * decisions happen in a short database transaction, while provider calls happen
 * after commit and are guarded by the existing content/version checks.
 */
@Service
public class ExternalDocumentService {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_BATCH_CONTENT_LENGTH = 5_000_000;

    private final RagDocumentRepository documentRepository;
    private final RagCollectionRepository collectionRepository;
    private final RagEmbeddingRepository embeddingRepository;
    private final DocumentVersionService documentVersionService;
    private final DocumentEmbedService documentEmbedService;
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ExternalDocumentService(
            RagDocumentRepository documentRepository,
            RagCollectionRepository collectionRepository,
            RagEmbeddingRepository embeddingRepository,
            DocumentVersionService documentVersionService,
            DocumentEmbedService documentEmbedService,
            EmbeddingProfileProvider embeddingProfileProvider,
            CollectionIdentityResolver collectionIdentityResolver,
            JdbcTemplate jdbcTemplate,
            @Nullable PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.embeddingRepository = embeddingRepository;
        this.documentVersionService = documentVersionService;
        this.documentEmbedService = documentEmbedService;
        this.embeddingProfileProvider = embeddingProfileProvider;
        this.collectionIdentityResolver = collectionIdentityResolver;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionManager == null
                ? null : new TransactionTemplate(transactionManager);
    }

    public ExternalDocumentUpsertResponse upsert(ExternalDocumentUpsertRequest request) {
        validateRequest(request);
        Long collectionId = resolveWritableCollection(request.getCollectionKey());
        Persisted persisted = persist(request, collectionId);
        return finishUpsert(persisted, request.isEmbed());
    }

    public ExternalDocumentBatchUpsertResponse batchUpsert(
            List<ExternalDocumentUpsertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (requests.size() > 50) {
            throw new IllegalArgumentException("External document batch is limited to 50 items");
        }
        long contentLength = requests.stream()
                .filter(Objects::nonNull)
                .map(ExternalDocumentUpsertRequest::getContent)
                .filter(Objects::nonNull)
                .mapToLong(String::length)
                .sum();
        if (contentLength > MAX_BATCH_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "External document batch content exceeds 5,000,000 characters");
        }

        List<ExternalDocumentUpsertResponse> results = new ArrayList<>(requests.size());
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int persistenceFailed = 0;
        int embeddingFailed = 0;
        for (ExternalDocumentUpsertRequest request : requests) {
            try {
                ExternalDocumentUpsertResponse result = upsert(request);
                results.add(result);
                switch (result.action()) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    default -> unchanged++;
                }
                if ("FAILED".equals(result.embeddingStatus())) {
                    embeddingFailed++;
                }
            } catch (RuntimeException e) {
                persistenceFailed++;
                results.add(failedResponse(request, e));
            }
        }
        return new ExternalDocumentBatchUpsertResponse(
                results,
                new ExternalDocumentBatchUpsertResponse.Summary(
                        requests.size(), created, updated, unchanged,
                        persistenceFailed, embeddingFailed));
    }

    public DocumentDetailResponse getByExternalIdentity(
            String collectionKey, String externalId) {
        String normalizedKey = requireText(collectionKey, "collectionKey", 128);
        String normalizedExternalId = normalizeRequired(externalId, "externalId", 255);
        RagCollection collection = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                normalizedKey, ApiKeyCollectionAccess.currentKey(), collectionIdentityResolver);
        RagDocument document = documentRepository
                .findByCollectionIdAndExternalId(collection.getId(), normalizedExternalId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.DOCUMENT_NOT_FOUND,
                        "Document not found for external identity"));
        if (RagDocument.JSON_RECORD.equals(document.getDocumentType())) {
            throw new DocumentRevisionConflictException(
                    "External identity belongs to a JSON record; use the JSON record API");
        }
        return toDetail(document);
    }

    public ExternalDocumentDeleteResponse sourceDelete(
            String collectionKey,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision) {
        String normalizedKey = requireText(collectionKey, "collectionKey", 128);
        String normalizedExternalId = normalizeRequired(externalId, "externalId", 255);
        String normalizedRevision = normalizeRequired(sourceRevision, "sourceRevision", 255);
        String normalizedExpected = normalizeOptional(expectedSourceRevision, 255);
        Long collectionId = resolveWritableCollection(normalizedKey);
        return executeInTransaction(() -> deleteInTransaction(
                collectionId, normalizedKey, normalizedExternalId,
                normalizedRevision, normalizedExpected));
    }

    private Persisted persist(ExternalDocumentUpsertRequest request, Long collectionId) {
        return executeInTransaction(() -> persistInTransaction(request, collectionId));
    }

    private Persisted persistInTransaction(
            ExternalDocumentUpsertRequest request, Long collectionId) {
        String externalId = normalizeRequired(request.getExternalId(), "externalId", 255);
        String sourceRevision = normalizeRequired(
                request.getSourceRevision(), "sourceRevision", 255);
        String expectedRevision = normalizeOptional(request.getExpectedSourceRevision(), 255);
        String title = normalizeRequired(request.getTitle(), "title", 255);
        String content = normalizeRequired(request.getContent(), "content", 1_000_000);
        String source = normalizeOptional(request.getSource(), 255);
        String documentType = request.getDocumentType() == null
                || request.getDocumentType().isBlank()
                ? "text" : requireText(request.getDocumentType(), "documentType", 50);
        if (RagDocument.JSON_RECORD.equals(documentType)) {
            throw new DocumentRevisionConflictException(
                    "documentType=json-record must use the JSON record API");
        }

        lockActiveCollection(collectionId);
        lockIdentity(collectionId, externalId);
        String contentHash = DigestUtils.sha256(content);
        RagDocument document = documentRepository
                .findByCollectionIdAndExternalId(collectionId, externalId)
                .orElse(null);

        if (document != null && RagDocument.JSON_RECORD.equals(document.getDocumentType())) {
            throw new DocumentRevisionConflictException(
                    "External identity belongs to a JSON record; use the JSON record API");
        }

        if (document == null) {
            if (expectedRevision != null) {
                throw conflict("expectedSourceRevision must be omitted for a new identity");
            }
            document = new RagDocument();
            document.setCollectionId(collectionId);
            document.setExternalId(externalId);
            document.setSourceRevision(sourceRevision);
            document.setTitle(title);
            document.setContent(content);
            document.setSource(source);
            document.setDocumentType(documentType);
            document.setMetadata(request.getMetadata());
            document.setContentHash(contentHash);
            document.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            document.setEnabled(true);
            document.setSourceDeletedAt(null);
            document.setProcessingStatus("PENDING");
            document.setProcessingError(null);
            document = documentRepository.saveAndFlush(document);
            RagDocumentVersion version = documentVersionService.forceRecordVersion(
                    document, "CREATE", "External document created");
            return new Persisted(document, "CREATED", true, version.getVersionNumber());
        }

        String currentRevision = normalizeOptional(document.getSourceRevision(), 255);
        boolean tombstone = !Boolean.TRUE.equals(document.getEnabled())
                || document.getSourceDeletedAt() != null;
        if (currentRevision != null && currentRevision.equals(sourceRevision)) {
            if (tombstone) {
                throw conflict("A tombstone revision cannot be replayed as an upsert");
            }
            if (sameManagedFields(document, title, contentHash, source, documentType,
                    request.getMetadata())) {
                int versionNumber = latestVersionNumber(document);
                return new Persisted(document, "UNCHANGED", false, versionNumber);
            }
            throw conflict("The same sourceRevision was used for different document content");
        }

        if (currentRevision == null) {
            if (expectedRevision != null) {
                throw conflict("Legacy external documents must be claimed without expectedSourceRevision");
            }
        } else if (expectedRevision != null
                && !expectedRevision.equals(currentRevision)) {
            throw conflict("expectedSourceRevision does not match the current source revision");
        }

        boolean contentChanged = !Objects.equals(document.getContentHash(), contentHash);
        document.setTitle(title);
        document.setContent(content);
        document.setSource(source);
        document.setDocumentType(documentType);
        document.setMetadata(request.getMetadata());
        document.setContentHash(contentHash);
        document.setSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        document.setExternalId(externalId);
        document.setSourceRevision(sourceRevision);
        document.setEnabled(true);
        document.setSourceDeletedAt(null);
        if (contentChanged) {
            document.setProcessingStatus("PENDING");
            document.setProcessingError(null);
        }
        document = documentRepository.saveAndFlush(document);
        RagDocumentVersion version = documentVersionService.forceRecordVersion(
                document, "UPDATE", contentChanged
                        ? "External document content updated"
                        : "External document metadata/source revision updated");
        return new Persisted(document, "UPDATED", contentChanged, version.getVersionNumber());
    }

    private ExternalDocumentDeleteResponse deleteInTransaction(
            Long collectionId,
            String collectionKey,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision) {
        lockActiveCollection(collectionId);
        lockIdentity(collectionId, externalId);
        RagDocument document = documentRepository
                .findByCollectionIdAndExternalId(collectionId, externalId)
                .orElseThrow(() -> new RagException(
                        ErrorCode.DOCUMENT_NOT_FOUND,
                        "Document not found for external identity"));
        if (RagDocument.JSON_RECORD.equals(document.getDocumentType())) {
            throw new DocumentRevisionConflictException(
                    "External identity belongs to a JSON record; use the JSON record API");
        }
        String currentRevision = normalizeOptional(document.getSourceRevision(), 255);
        boolean tombstone = !Boolean.TRUE.equals(document.getEnabled())
                || document.getSourceDeletedAt() != null;
        if (tombstone && sourceRevision.equals(currentRevision)) {
            return new ExternalDocumentDeleteResponse(
                    document.getId(), collectionKey, externalId, currentRevision,
                    "UNCHANGED", latestVersionNumber(document), false,
                    document.getSourceDeletedAt(), null, null);
        }
        if (!tombstone && sourceRevision.equals(currentRevision)) {
            throw conflict("A source deletion must use a new sourceRevision");
        }
        if (expectedSourceRevision != null
                && !expectedSourceRevision.equals(currentRevision)) {
            throw conflict("expectedSourceRevision does not match the current source revision");
        }
        document.setEnabled(false);
        document.setSourceRevision(sourceRevision);
        document.setSourceDeletedAt(LocalDateTime.now());
        document = documentRepository.saveAndFlush(document);
        RagDocumentVersion version = documentVersionService.forceRecordVersion(
                document, "DELETE", "External document source deleted");
        return new ExternalDocumentDeleteResponse(
                document.getId(), collectionKey, externalId, sourceRevision,
                "DELETED", version.getVersionNumber(), false,
                document.getSourceDeletedAt(), null, null);
    }

    private ExternalDocumentUpsertResponse finishUpsert(
            Persisted persisted, boolean embed) {
        RagDocument document = persisted.document();
        String embeddingStatus = "NOT_REQUESTED";
        String embeddingProfileKey = null;
        String errorCode = null;
        String error = null;
        boolean fresh = documentEmbedService.hasFreshEmbedding(document);
        if (embed && Boolean.TRUE.equals(document.getEnabled()) && !fresh) {
            try {
                Map<String, Object> result = documentEmbedService.embedDocument(
                        document.getId(), false);
                embeddingStatus = String.valueOf(
                        result.getOrDefault("status", "FAILED"));
                embeddingProfileKey = String.valueOf(
                        result.getOrDefault("embeddingProfileKey",
                                embeddingProfileProvider.getActiveProfile().profileKey()));
                if ("FAILED".equals(embeddingStatus)) {
                    errorCode = ErrorCode.EMBEDDING_FAILED.getCode();
                    error = safeError(result.get("error"));
                }
            } catch (RuntimeException e) {
                embeddingStatus = "FAILED";
                errorCode = ErrorCode.EMBEDDING_FAILED.getCode();
                error = safeError(e);
            }
        } else if (embed && fresh) {
            embeddingStatus = "CACHED";
            embeddingProfileKey = embeddingProfileProvider.getActiveProfile().profileKey();
        }

        RagDocument reloaded = documentRepository.findById(document.getId()).orElse(document);
        fresh = documentEmbedService.hasFreshEmbedding(reloaded);
        if (embeddingProfileKey == null) {
            embeddingProfileKey = embeddingProfileProvider.getActiveProfile().profileKey();
        }
        return new ExternalDocumentUpsertResponse(
                reloaded.getId(),
                collectionKeyFor(reloaded.getCollectionId()),
                reloaded.getExternalId(),
                reloaded.getSourceRevision(),
                persisted.action(),
                persisted.contentChanged(),
                persisted.versionNumber(),
                embeddingStatus,
                embeddingProfileKey,
                fresh,
                reloaded.getProcessingStatus(),
                reloaded.getSourceDeletedAt(),
                errorCode,
                error);
    }

    private DocumentDetailResponse toDetail(RagDocument document) {
        Long collectionId = document.getCollectionId();
        Map<Long, String> names = collectionId == null
                ? Map.of()
                : collectionRepository.findById(collectionId)
                .map(c -> Map.of(collectionId, c.getName()))
                .orElseGet(Map::of);
        Map<Long, String> keys = collectionId == null
                ? Map.of()
                : collectionRepository.findById(collectionId)
                .map(c -> Map.of(collectionId, c.getCollectionKey()))
                .orElseGet(Map::of);
        return DocumentMapper.toDetailResponse(
                document, names, keys, embeddingRepository,
                embeddingProfileProvider.getActiveProfile().id());
    }

    private Long resolveWritableCollection(String collectionKey) {
        RagCollection collection = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                collectionKey,
                ApiKeyCollectionAccess.currentKey(),
                collectionIdentityResolver);
        return ApiKeyCollectionAccess.resolveWritableCollectionId(
                collection.getId(), ApiKeyCollectionAccess.currentKey());
    }

    private String collectionKeyFor(Long collectionId) {
        if (collectionId == null) {
            return null;
        }
        return collectionRepository.findById(collectionId)
                .map(RagCollection::getCollectionKey)
                .orElse(null);
    }

    private void lockIdentity(Long collectionId, String externalId) {
        String lockKey = collectionId + ":external-document:" + externalId;
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, lockKey);
                statement.execute();
            }
            return null;
        });
    }

    private void lockActiveCollection(Long collectionId) {
        if (transactionTemplate != null) {
            collectionIdentityResolver.requireActiveForShare(collectionId);
        }
    }

    private boolean sameManagedFields(
            RagDocument document,
            String title,
            String contentHash,
            String source,
            String documentType,
            Map<String, Object> metadata) {
        return Objects.equals(document.getTitle(), title)
                && Objects.equals(document.getContentHash(), contentHash)
                && Objects.equals(document.getSource(), source)
                && Objects.equals(document.getDocumentType(), documentType)
                && Objects.equals(document.getMetadata(), metadata)
                && Boolean.TRUE.equals(document.getEnabled())
                && document.getSourceDeletedAt() == null;
    }

    private int latestVersionNumber(RagDocument document) {
        return documentVersionService.getLatestVersion(document.getId())
                .map(RagDocumentVersion::getVersionNumber)
                .orElse(0);
    }

    private <T> T executeInTransaction(java.util.function.Supplier<T> callback) {
        if (transactionTemplate == null) {
            return callback.get();
        }
        T result = transactionTemplate.execute(status -> callback.get());
        return Objects.requireNonNull(result, "transaction callback returned null");
    }

    private void validateRequest(ExternalDocumentUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        requireText(request.getCollectionKey(), "collectionKey", 128);
        normalizeRequired(request.getExternalId(), "externalId", 255);
        normalizeRequired(request.getSourceRevision(), "sourceRevision", 255);
        normalizeRequired(request.getTitle(), "title", 255);
        normalizeRequired(request.getContent(), "content", 1_000_000);
        normalizeOptional(request.getExpectedSourceRevision(), 255);
        normalizeOptional(request.getSource(), 255);
        if (request.getDocumentType() != null && !request.getDocumentType().isBlank()) {
            requireText(request.getDocumentType(), "documentType", 50);
        }
    }

    private String requireText(String value, String field, int maxLength) {
        String normalized = normalizeRequired(value, field, maxLength);
        if ("collectionKey".equals(field)
                && !com.springairag.api.validation.CollectionKeyValidator.isValid(normalized)) {
            throw new IllegalArgumentException(
                    "collectionKey must contain 1-128 visible ASCII characters");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String field, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "value must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private DocumentRevisionConflictException conflict(String message) {
        return new DocumentRevisionConflictException(message);
    }

    private String safeError(Object error) {
        if (error == null) {
            return null;
        }
        return safeError(String.valueOf(error));
    }

    private String safeError(Throwable error) {
        return safeError(error == null ? null : error.getMessage());
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "Embedding failed";
        }
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(error);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked : masked.substring(0, MAX_ERROR_LENGTH);
    }

    private ExternalDocumentUpsertResponse failedResponse(
            ExternalDocumentUpsertRequest request, RuntimeException error) {
        String code = error instanceof RagException rag
                ? rag.getErrorCode() : ErrorCode.BAD_REQUEST.getCode();
        return new ExternalDocumentUpsertResponse(
                null,
                request == null ? null : request.getCollectionKey(),
                request == null ? null : request.getExternalId(),
                request == null ? null : request.getSourceRevision(),
                "PERSISTENCE_FAILED",
                false,
                0,
                "FAILED",
                null,
                false,
                "FAILED",
                null,
                code,
                safeError(error));
    }

    private record Persisted(
            RagDocument document,
            String action,
            boolean contentChanged,
            int versionNumber) {
    }
}
