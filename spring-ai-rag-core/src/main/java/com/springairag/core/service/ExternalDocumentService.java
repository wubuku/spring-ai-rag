package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentBatchUpsertResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.ExternalDocumentUpsertResponse;
import com.springairag.api.dto.DocumentDetailResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.util.DocumentMapper;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import org.springframework.stereotype.Service;

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
    private final EmbeddingProfileProvider embeddingProfileProvider;
    private final CollectionIdentityResolver collectionIdentityResolver;
    private DocumentMutationService mutationService; // optional-claim: DocumentMutationService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是切到 legacy 内联写入路径——那条路径只在 Spring 装配之外可达
    private ExternalAddressRetirementService addressRetirementService; // optional-claim: ExternalAddressRetirementService 是无条件 @Service，null 臂只在不走 Spring 装配的构造路径可达；守卫真正的职责是退役校验缺席时放行——它防的是"已退役的外部地址被重新写活"，属于可跳过的旁路而非写入前置条件

    public ExternalDocumentService(
            RagDocumentRepository documentRepository,
            RagCollectionRepository collectionRepository,
            RagEmbeddingRepository embeddingRepository,
            EmbeddingProfileProvider embeddingProfileProvider,
            CollectionIdentityResolver collectionIdentityResolver) {
        this.documentRepository = documentRepository;
        this.collectionRepository = collectionRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingProfileProvider = embeddingProfileProvider;
        this.collectionIdentityResolver = collectionIdentityResolver;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMutationService(DocumentMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAddressRetirementService(
            ExternalAddressRetirementService addressRetirementService) {
        this.addressRetirementService = addressRetirementService;
    }

    public ExternalDocumentUpsertResponse upsert(ExternalDocumentUpsertRequest request) {
        return mutationService.upsertExternal(request);
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
        return getByExternalIdentity(collectionKey, "default", externalId);
    }

    public DocumentDetailResponse getByExternalIdentity(
            String collectionKey,
            String sourceNamespace,
            String externalId) {
        String normalizedKey = requireText(collectionKey, "collectionKey", 128);
        String normalizedNamespace = normalizeNamespace(sourceNamespace);
        String normalizedExternalId = normalizeRequired(externalId, "externalId", 255);
        RagCollection collection = ApiKeyCollectionAccess.requireActiveCollectionByKey(
                normalizedKey, ApiKeyCollectionAccess.currentPolicy(), collectionIdentityResolver);
        if (addressRetirementService != null) {
            addressRetirementService.requireNotRetired(
                    collection.getId(), normalizedNamespace, normalizedExternalId);
        }
        RagDocument document = documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        collection.getId(), normalizedNamespace, normalizedExternalId)
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
        return sourceDelete(
                collectionKey, "default", externalId,
                sourceRevision, expectedSourceRevision);
    }

    public ExternalDocumentDeleteResponse sourceDelete(
            String collectionKey,
            String sourceNamespace,
            String externalId,
            String sourceRevision,
            String expectedSourceRevision) {
        return mutationService.tombstoneExternal(
                collectionKey, sourceNamespace, externalId,
                sourceRevision, expectedSourceRevision, false);
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


    private String normalizeNamespace(String value) {
        String normalized = value == null || value.isBlank()
                ? "default" : value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "sourceNamespace must not exceed 128 characters");
        }
        return normalized;
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

}
