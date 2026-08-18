package com.springairag.core.embeddingjob;

import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.api.dto.EmbeddingJobBatchResponse;
import com.springairag.api.dto.EmbeddingJobCreateRequest;
import com.springairag.api.dto.EmbeddingJobPageResponse;
import com.springairag.api.dto.EmbeddingJobResponse;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagEmbeddingJobProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * embedding job 创建、授权、查询、取消和重试。
 */
@Service
public class EmbeddingJobService {

    private static final Pattern SHA256 =
            Pattern.compile("[0-9a-fA-F]{64}");

    private final EmbeddingJobRepository jobRepository;
    private final RagDocumentRepository documentRepository;
    private final CollectionRetrievalScopeResolver scopeResolver;
    private final EmbeddingProfileProvider profileProvider;
    private final RagEmbeddingJobProperties properties;

    public EmbeddingJobService(
            EmbeddingJobRepository jobRepository,
            RagDocumentRepository documentRepository,
            CollectionRetrievalScopeResolver scopeResolver,
            EmbeddingProfileProvider profileProvider,
            RagProperties properties) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.scopeResolver = scopeResolver;
        this.profileProvider = profileProvider;
        this.properties = properties.getEmbeddingJobs();
    }

    @Transactional
    public EmbeddingJobBatchResponse create(
            EmbeddingJobCreateRequest request) {
        requireEnabled();
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        List<Long> documentIds = resolveDocumentIds(request);
        if (documentIds.isEmpty()) {
            return new EmbeddingJobBatchResponse(
                    UUID.randomUUID(), 0, 0, 0, List.of());
        }
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        int maxAttempts = resolveMaxAttempts(request.maxAttempts());
        UUID requestedBatch = UUID.randomUUID();
        List<EmbeddingJobResponse> responses =
                new ArrayList<>(documentIds.size());
        int coalesced = 0;
        for (Long documentId : documentIds) {
            RagDocument document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            requireDocumentUsable(document);
            EmbeddingJobRepository.CreateResult result =
                    jobRepository.createOrCoalesce(
                            requestedBatch,
                            document.getId(),
                            profile.id(),
                            document.getContentHash(),
                            document.getVersion() != null
                                    ? document.getVersion()
                                    : 0L,
                            request.force(),
                            maxAttempts,
                            "API",
                            ChatPrincipal.fromCurrentRequest().id());
            if (result.coalesced()) {
                coalesced++;
            }
            responses.add(toResponse(
                    result.job(), result.coalesced()));
        }
        return new EmbeddingJobBatchResponse(
                requestedBatch,
                documentIds.size(),
                documentIds.size() - coalesced,
                coalesced,
                responses);
    }

    public EmbeddingJobResponse get(UUID id) {
        EmbeddingJob job = requireAuthorized(id);
        return toResponse(job, false);
    }

    public List<EmbeddingJobResponse> list(
            UUID batchId,
            EmbeddingJobStatus status,
            int page,
            int size) {
        return listPage(batchId, status, null, page, size).items();
    }

    public EmbeddingJobPageResponse listPage(
            UUID batchId,
            EmbeddingJobStatus status,
            String collectionKey,
            int page,
            int size) {
        RagApiKey caller = ApiKeyCollectionAccess.currentKey();
        Long collectionId = resolveListCollectionId(collectionKey, caller);
        java.util.List<Long> allowed = ApiKeyCollectionAccess
                .restrictedCollectionIds(caller)
                .map(java.util.ArrayList::new)
                .orElse(null);
        int pageSize = Math.max(1, Math.min(200, size));
        int pageIndex = Math.max(0, page);
        EmbeddingJobRepository.PageResult result = jobRepository.listPage(
                batchId,
                status,
                collectionId,
                allowed,
                pageSize,
                pageIndex * pageSize);
        int totalPages = result.totalElements() == 0
                ? 0
                : (int) Math.ceil(result.totalElements() / (double) pageSize);
        return new EmbeddingJobPageResponse(
                result.items().stream()
                        .map(job -> toResponse(job, false))
                        .toList(),
                pageIndex,
                pageSize,
                result.totalElements(),
                totalPages);
    }

    public CollectionEmbeddingReadinessResponse readiness(String collectionKey) {
        RagApiKey caller = ApiKeyCollectionAccess.currentKey();
        Long collectionId = resolveListCollectionId(collectionKey, caller);
        if (collectionId == null) {
            throw new IllegalArgumentException("collectionKey is required");
        }
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        return jobRepository.readiness(collectionId, collectionKey, profile);
    }

    private Long resolveListCollectionId(String collectionKey, RagApiKey caller) {
        if (collectionKey == null || collectionKey.isBlank()) {
            return null;
        }
        RetrievalScope scope = scopeResolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null,
                List.of(collectionKey),
                null,
                null,
                caller);
        if (scope.matchNone() || scope.collectionIds().isEmpty()) {
            throw new SecurityException("Collection is not authorized");
        }
        return scope.collectionIds().getFirst();
    }

    public EmbeddingJobResponse cancel(UUID id) {
        EmbeddingJob current = requireAuthorized(id);
        EmbeddingJob updated = jobRepository.cancel(id)
                .orElse(current);
        return toResponse(updated, false);
    }

    public EmbeddingJobResponse retry(UUID id, Integer requestedMaxAttempts) {
        requireEnabled();
        EmbeddingJob current = requireAuthorized(id);
        int maxAttempts = requestedMaxAttempts != null
                ? resolveMaxAttempts(requestedMaxAttempts)
                : current.maxAttempts();
        try {
            return jobRepository.retry(id, maxAttempts)
                    .map(job -> toResponse(job, false))
                    .orElseGet(() -> activeRetryTarget(current)
                            .map(job -> toResponse(job, true))
                            .orElseThrow(() -> new RagException(
                                    ErrorCode.DUPLICATE_RESOURCE,
                                    "Only FAILED, STALE, or CANCELLED jobs "
                                            + "without another active job can be retried")));
        } catch (DataIntegrityViolationException race) {
            return activeRetryTarget(current)
                    .map(job -> toResponse(job, true))
                    .orElseThrow(() -> race);
        }
    }

    private java.util.Optional<EmbeddingJob> activeRetryTarget(
            EmbeddingJob current) {
        return jobRepository.findActive(
                current.documentId(),
                current.embeddingProfileId(),
                current.contentHash());
    }

    private List<Long> resolveDocumentIds(
            EmbeddingJobCreateRequest request) {
        boolean idsPresent = request.documentIds() != null;
        boolean scopePresent = request.collectionScopeMode() != null
                || request.collectionIds() != null
                || request.collectionKeys() != null;
        if (idsPresent == scopePresent) {
            throw new IllegalArgumentException(
                    "Provide either documentIds or a Collection scope");
        }
        if (idsPresent) {
            List<Long> ids = normalizeDocumentIds(request.documentIds());
            RagApiKey caller = ApiKeyCollectionAccess.currentKey();
            for (Long id : ids) {
                RagDocument document = documentRepository.findById(id)
                        .orElseThrow(() -> new DocumentNotFoundException(id));
                ApiKeyCollectionAccess.requireDocumentAccess(document, caller);
            }
            return ids;
        }

        RetrievalScope scope = scopeResolver.resolve(
                request.collectionScopeMode(),
                request.collectionIds(),
                request.collectionKeys(),
                null,
                null,
                ApiKeyCollectionAccess.currentKey());
        int fetchLimit = properties.getMaxDocumentsPerBatch() + 1;
        List<Long> ids;
        if (scope.matchNone()) {
            ids = List.of();
        } else {
            ids = switch (scope.collectionFilter()) {
                case NONE -> documentRepository.findEnabledIds(
                        PageRequest.of(0, fetchLimit));
                case ANY_ASSIGNED -> documentRepository.findEnabledAssignedIds(
                        PageRequest.of(0, fetchLimit));
                case SELECTED -> documentRepository.findEnabledIdsByCollectionIds(
                        scope.collectionIds(),
                        PageRequest.of(0, fetchLimit));
            };
        }
        if (ids.size() > properties.getMaxDocumentsPerBatch()) {
            throw new IllegalArgumentException(
                    "Collection scope expands to more than "
                            + properties.getMaxDocumentsPerBatch()
                            + " documents");
        }
        return List.copyOf(ids);
    }

    private List<Long> normalizeDocumentIds(List<Long> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "documentIds must not be empty");
        }
        if (values.size() > properties.getMaxDocumentsPerBatch()) {
            throw new IllegalArgumentException(
                    "documentIds must not contain more than "
                            + properties.getMaxDocumentsPerBatch() + " items");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(
                        "documentIds must contain positive IDs");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private void requireDocumentUsable(RagDocument document) {
        if (!Boolean.TRUE.equals(document.getEnabled())) {
            throw new IllegalArgumentException(
                    "Document is disabled: " + document.getId());
        }
        if (document.getContentHash() == null
                || !SHA256.matcher(document.getContentHash()).matches()) {
            throw new IllegalStateException(
                    "Document has no valid contentHash: " + document.getId());
        }
    }

    private EmbeddingJob requireAuthorized(UUID id) {
        EmbeddingJob job = jobRepository.find(id)
                .orElseThrow(() -> new RagException(
                        ErrorCode.NOT_FOUND, "Embedding job not found"));
        RagDocument document = documentRepository.findById(job.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(job.documentId()));
        ApiKeyCollectionAccess.requireDocumentAccess(
                document, ApiKeyCollectionAccess.currentKey());
        return job;
    }

    private boolean isVisible(long documentId, RagApiKey caller) {
        return documentRepository.findById(documentId)
                .map(document -> {
                    try {
                        ApiKeyCollectionAccess.requireDocumentAccess(
                                document, caller);
                        return true;
                    } catch (SecurityException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private int resolveMaxAttempts(Integer requested) {
        int value = requested != null
                ? requested
                : properties.getDefaultMaxAttempts();
        if (value < 1 || value > properties.getMaxAttempts()) {
            throw new IllegalArgumentException(
                    "maxAttempts must be between 1 and "
                            + properties.getMaxAttempts());
        }
        return value;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new RagException(
                    ErrorCode.EMBEDDING_JOBS_DISABLED,
                    "Persistent embedding jobs are disabled");
        }
    }

    private EmbeddingJobResponse toResponse(
            EmbeddingJob job, boolean coalesced) {
        return new EmbeddingJobResponse(
                job.id(),
                job.batchId(),
                job.documentId(),
                job.embeddingProfileId(),
                job.force(),
                job.contentHash(),
                job.documentVersion(),
                job.status().name(),
                job.attemptCount(),
                job.maxAttempts(),
                job.availableAt(),
                job.leaseExpiresAt(),
                job.cancelRequestedAt(),
                job.lastError(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.updatedAt(),
                coalesced,
                job.origin(),
                job.requestedByPrincipalId());
    }
}
