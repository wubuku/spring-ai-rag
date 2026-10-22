package com.springairag.core.documentprocessing;

import com.springairag.api.dto.DocumentProcessingRunPageResponse;
import com.springairag.api.dto.DocumentProcessingRunResponse;
import com.springairag.api.dto.DocumentProcessingStageResponse;
import com.springairag.api.dto.DocumentProcessingSummary;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.api.enums.DocumentProcessingStageStatus;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagDocumentProcessingProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingJob;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.security.ApiKeyCollectionAccess;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 文档处理 Run 的创建、授权、动作和读模型服务。 */
@Service
public class DocumentProcessingRunService {

    private final DocumentProcessingRunRepository repository;
    private final EmbeddingJobRepository jobRepository;
    private final RagDocumentRepository documentRepository;
    private final EmbeddingProfileProvider profileProvider;
    private final DocumentDerivationDescriptorProvider descriptorProvider;
    private final CollectionRetrievalScopeResolver scopeResolver;
    private final RagDocumentProcessingProperties properties;

    public DocumentProcessingRunService(
            DocumentProcessingRunRepository repository,
            EmbeddingJobRepository jobRepository,
            RagDocumentRepository documentRepository,
            EmbeddingProfileProvider profileProvider,
            DocumentDerivationDescriptorProvider descriptorProvider,
            CollectionRetrievalScopeResolver scopeResolver,
            RagProperties ragProperties) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.profileProvider = profileProvider;
        this.descriptorProvider = descriptorProvider;
        this.scopeResolver = scopeResolver;
        this.properties = ragProperties.getDocumentProcessing();
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    @Transactional
    public DocumentProcessingRun ensureForJob(
            EmbeddingJob job,
            String origin,
            UUID supersedesRunId,
            boolean legacyBridge) {
        if (!enabled()) {
            return null;
        }
        return repository.ensureForJob(
                job,
                origin,
                job.requestedByPrincipalId() != null
                        ? job.requestedByPrincipalId()
                        : ChatPrincipal.fromCurrentRequest().id(),
                supersedesRunId,
                legacyBridge);
    }

    @Transactional
    public DocumentProcessingRun ensureBridgeForJob(EmbeddingJob job) {
        if (!enabled() || job == null) {
            return null;
        }
        var existing = repository.findByJobId(job.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!bridgeEligible(job)) {
            return null;
        }
        return ensureForJob(job, job.origin(), null, true);
    }

    public Map<UUID, UUID> ensureBridgesForJobs(List<EmbeddingJob> jobs) {
        if (!enabled() || jobs == null || jobs.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<UUID, UUID> result = new java.util.LinkedHashMap<>(
                findRunIdsByJobIds(jobs.stream().map(EmbeddingJob::id).toList()));
        for (EmbeddingJob job : jobs) {
            if (!result.containsKey(job.id()) && bridgeEligible(job)) {
                DocumentProcessingRun bridge = ensureBridgeForJob(job);
                if (bridge != null) {
                    result.put(job.id(), bridge.id());
                }
            }
        }
        return result;
    }

    private boolean bridgeEligible(EmbeddingJob job) {
        return job.status() == EmbeddingJobStatus.QUEUED
                || job.status() == EmbeddingJobStatus.RUNNING
                || job.status() == EmbeddingJobStatus.STALE;
    }

    public DocumentProcessingRunResponse get(UUID runId) {
        requireEnabled();
        return toResponse(requireAuthorized(runId), null);
    }

    public UUID findRunIdByJobId(UUID jobId) {
        if (!enabled()) {
            return null;
        }
        return repository.findByJobId(jobId).map(DocumentProcessingRun::id)
                .orElse(null);
    }

    public Map<UUID, UUID> findRunIdsByJobIds(List<UUID> jobIds) {
        if (!enabled()) {
            return Map.of();
        }
        return repository.findRunIdsByJobIds(jobIds);
    }

    public int markSuperseded(
            long documentId,
            long profileId,
            long currentGeneration) {
        if (!enabled()) {
            return 0;
        }
        return repository.markSuperseded(documentId, profileId, currentGeneration);
    }

    public DocumentProcessingSummary summaryForDocument(RagDocument document) {
        if (!enabled() || document == null
                || !Boolean.TRUE.equals(document.getEnabled())) {
            return null;
        }
        EmbeddingProfile profile;
        DocumentDerivationDescriptorProvider.Descriptor descriptor;
        try {
            profile = document.getCollectionId() == null
                    ? profileProvider.getActiveProfile()
                    : profileProvider.getProfileForCollection(document.getCollectionId());
            descriptor = descriptorProvider.describe(document);
        } catch (RuntimeException ignored) {
            return null;
        }
        OptionalRun current = repository.findCurrent(
                document.getId(), profile.id(),
                currentGeneration(document.getId(), profile.id()),
                document.getContentHash(), descriptor.chunkerVersion())
                .map(OptionalRun::new)
                .orElseGet(() -> repository.findByDocumentLatest(
                        document.getId(), profile.id()).map(OptionalRun::new)
                        .orElse(null));
        if (current == null) {
            var activeJob = jobRepository.findCurrentActive(
                    document.getId(), profile.id(), document.getContentHash(),
                    descriptor.documentKind(), descriptor.chunkerVersion());
            if (activeJob.isEmpty()) {
                return null;
            }
            current = new OptionalRun(ensureBridgeForJob(activeJob.get()));
        }
        DocumentProcessingRun run = current.run();
        return new DocumentProcessingSummary(
                run.id(), run.status(), run.activeStage(), run.executionAttempt(),
                run.maxAttempts(), run.nextRetryAt(),
                retryable(run, document, profile, descriptor),
                run.status() == DocumentProcessingRunStatus.DEAD_LETTERED,
                !run.legacyBridge(), run.updatedAt(), run.lastErrorCode(),
                run.lastError());
    }

    public DocumentProcessingRunPageResponse list(
            Long documentId,
            String collectionKey,
            DocumentProcessingRunStatus status,
            Boolean deadLettered,
            int page,
            int size) {
        requireEnabled();
        Long collectionId = resolveCollectionId(collectionKey);
        List<Long> allowed = ApiKeyCollectionAccess
                .restrictedCollectionIds(ApiKeyCollectionAccess.currentPolicy())
                .map(java.util.ArrayList::new)
                .orElse(null);
        if (collectionId != null) {
            allowed = List.of(collectionId);
        }
        int pageIndex = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(200, size));
        DocumentProcessingRunRepository.PageResult result = repository.listPage(
                documentId, status, deadLettered, allowed,
                pageSize, pageIndex * pageSize);
        int totalPages = result.totalElements() == 0
                ? 0
                : (int) Math.ceil(result.totalElements() / (double) pageSize);
        return new DocumentProcessingRunPageResponse(
                result.items().stream()
                        .map(run -> toResponse(run, null))
                        .toList(),
                pageIndex, pageSize, result.totalElements(), totalPages);
    }

    @Transactional
    public DocumentProcessingRunResponse cancel(UUID runId) {
        requireEnabled();
        DocumentProcessingRun run = requireAuthorized(runId);
        if (!run.active()) {
            return toResponse(run, null);
        }
        if (run.embeddingJobId() != null) {
            jobRepository.cancel(run.embeddingJobId());
            jobRepository.find(run.embeddingJobId()).ifPresent(repository::mirrorJob);
        }
        return get(runId);
    }

    @Transactional
    public DocumentProcessingRunResponse retry(UUID runId, Integer requestedMaxAttempts) {
        requireEnabled();
        DocumentProcessingRun source = requireAuthorized(runId);
        if (source.active()) {
            throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Active document processing run cannot be retried");
        }
        RagDocument document = documentRepository.findById(source.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(source.documentId()));
        if (!Boolean.TRUE.equals(document.getEnabled())) {
            throw new RagException(ErrorCode.DOCUMENT_DISABLED,
                    "Disabled documents cannot be retried");
        }
        EmbeddingProfile profile = profileProvider.findRequiredById(
                source.embeddingProfileId());
        DocumentDerivationDescriptorProvider.Descriptor descriptor =
                descriptorProvider.describe(document);
        if (!retryable(source, document, profile, descriptor)) {
            throw new RagException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Processing run snapshot is no longer retryable");
        }
        var active = jobRepository.findCurrentActive(
                document.getId(), profile.id(), document.getContentHash(),
                descriptor.documentKind(), descriptor.chunkerVersion());
        if (active.isPresent()) {
            DocumentProcessingRun target = repository.findByJobId(active.get().id())
                    .orElseGet(() -> ensureForJob(active.get(), "API", null, true));
            return toResponse(target, Boolean.TRUE);
        }
        int maxAttempts = requestedMaxAttempts == null
                ? source.maxAttempts()
                : Math.max(1, Math.min(10, requestedMaxAttempts));
        long generation = jobRepository.allocateGeneration(
                document.getId(), profile.id(), document.getContentHash(),
                descriptor.chunkerVersion(), false);
        jobRepository.cancelSuperseded(document.getId(), profile.id(), generation);
        repository.markSuperseded(document.getId(), profile.id(), generation);
        EmbeddingJob created = jobRepository.createOrCoalesce(
                        UUID.randomUUID(), document.getId(), profile.id(),
                        document.getContentHash(),
                        document.getVersion() == null ? 0L : document.getVersion(),
                        true, maxAttempts, "API",
                        ChatPrincipal.fromCurrentRequest().id(), generation,
                        descriptor.documentKind(), descriptor.chunkerVersion())
                .job();
        jobRepository.updateDocumentProcessing(
                document.getId(), "PENDING", null);
        DocumentProcessingRun target = ensureForJob(
                created, "API", source.id(), false);
        jobRepository.activateJob(
                document.getId(), profile.id(), generation, created.id());
        return toResponse(target, Boolean.FALSE);
    }

    public DocumentProcessingRun requireAuthorized(UUID runId) {
        DocumentProcessingRun run = repository.find(runId)
                .orElseThrow(() -> new DocumentNotFoundException(
                        "Document processing run not found"));
        RagDocument document = documentRepository.findById(run.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(run.documentId()));
        ApiKeyCollectionAccess.requireDocumentAccess(
                document, ApiKeyCollectionAccess.currentPolicy());
        return run;
    }

    private DocumentProcessingRunResponse toResponse(
            DocumentProcessingRun run,
            Boolean coalesced) {
        List<DocumentProcessingStageResponse> stages = repository.stages(run.id())
                .stream()
                .map(this::toStageResponse)
                .toList();
        return new DocumentProcessingRunResponse(
                run.id(), run.documentId(), run.embeddingProfileId(),
                run.embeddingJobId(), run.requestGeneration(),
                run.documentVersion(), run.contentHash(), run.documentKind(),
                run.chunkerVersion(), run.attemptNo(), run.executionAttempt(),
                run.maxAttempts(), run.status(), run.activeStage(),
                run.nextRetryAt(), run.cancelRequestedAt(), run.deadLetteredAt(),
                run.deadLetterReason(), run.lastErrorCode(), run.lastError(),
                run.origin(), run.supersedesRunId(), run.legacyBridge(),
                run.createdAt(), run.startedAt(), run.finishedAt(), run.updatedAt(),
                stages, coalesced);
    }

    private DocumentProcessingStageResponse toStageResponse(
            DocumentProcessingStage stage) {
        return new DocumentProcessingStageResponse(
                stage.stageName(), stage.stageOrder(), stage.status(),
                stage.executionAttempt(), stage.progressCurrent(),
                stage.progressTotal(), stage.progressLabel(), stage.startedAt(),
                stage.finishedAt(), stage.lastHeartbeatAt(), stage.updatedAt(),
                stage.lastErrorCode(), stage.lastError());
    }

    private boolean retryable(
            DocumentProcessingRun run,
            RagDocument document,
            EmbeddingProfile profile,
            DocumentDerivationDescriptorProvider.Descriptor descriptor) {
        if (run.status() == DocumentProcessingRunStatus.QUEUED
                || run.status() == DocumentProcessingRunStatus.RUNNING
                || run.status() == DocumentProcessingRunStatus.COMPLETED
                || !Boolean.TRUE.equals(document.getEnabled())) {
            return false;
        }
        boolean sameSnapshot = run.embeddingProfileId() == profile.id()
                && run.contentHash().equals(document.getContentHash())
                && run.chunkerVersion().equals(descriptor.chunkerVersion());
        if (!sameSnapshot) {
            return false;
        }
        if (run.status() == DocumentProcessingRunStatus.STALE) {
            return jobRepository.findCurrentActive(
                    document.getId(), profile.id(), document.getContentHash(),
                    descriptor.documentKind(), descriptor.chunkerVersion()).isEmpty();
        }
        return true;
    }

    private long currentGeneration(long documentId, long profileId) {
        return jobRepository.findCurrentGeneration(documentId, profileId);
    }

    private Long resolveCollectionId(String collectionKey) {
        if (collectionKey == null || collectionKey.isBlank()) {
            return null;
        }
        RetrievalScope scope = scopeResolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of(collectionKey), null, null,
                ApiKeyCollectionAccess.currentPolicy());
        if (scope.matchNone() || scope.collectionIds().isEmpty()) {
            throw new SecurityException("Collection is not authorized");
        }
        return scope.collectionIds().getFirst();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new RagException(ErrorCode.EMBEDDING_JOBS_DISABLED,
                    "Document processing runs are disabled");
        }
    }

    private record OptionalRun(DocumentProcessingRun run) {}
}
