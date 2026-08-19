package com.springairag.core.embeddingjob;

import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagEmbeddingJobProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 在文档持久化后按 SYNC/ASYNC/SKIP 分发嵌入。
 */
@Service
public class EmbeddingDispatchService {

    private final DocumentEmbedService documentEmbedService;
    private final EmbeddingJobRepository jobRepository;
    private final EmbeddingProfileProvider profileProvider;
    private final RagEmbeddingJobProperties jobProperties;
    private final DocumentDerivationDescriptorProvider descriptorProvider;
    private final EmbeddingJobExecutor jobExecutor;

    @Autowired
    public EmbeddingDispatchService(
            DocumentEmbedService documentEmbedService,
            EmbeddingJobRepository jobRepository,
            EmbeddingProfileProvider profileProvider,
            RagProperties ragProperties,
            DocumentDerivationDescriptorProvider descriptorProvider,
            EmbeddingJobExecutor jobExecutor) {
        this.documentEmbedService = documentEmbedService;
        this.jobRepository = jobRepository;
        this.profileProvider = profileProvider;
        this.jobProperties = ragProperties.getEmbeddingJobs();
        this.descriptorProvider = descriptorProvider;
        this.jobExecutor = jobExecutor;
    }

    public EmbeddingDispatchService(
            DocumentEmbedService documentEmbedService,
            EmbeddingJobRepository jobRepository,
            EmbeddingProfileProvider profileProvider,
            RagProperties ragProperties) {
        this(documentEmbedService, jobRepository, profileProvider, ragProperties,
                null, null);
    }

    public Result dispatchAfterCommit(
            RagDocument document,
            EmbeddingPolicy policy,
            boolean contentChanged,
            String origin) {
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        if (policy == EmbeddingPolicy.SKIP) {
            return markNotRequestedInCurrentTransaction(document);
        }
        Result queued = enqueueInCurrentTransaction(
                document, contentChanged, false, origin);
        return policy == EmbeddingPolicy.SYNC
                ? completeAfterCommit(queued)
                : queued;
    }

    @Transactional
    public Result enqueueInCurrentTransaction(
            RagDocument document,
            boolean contentChanged,
            boolean force,
            String origin) {
        if (!jobProperties.isEnabled()) {
            throw new RagException(
                    ErrorCode.EMBEDDING_JOBS_DISABLED,
                    "Persistent embedding jobs are disabled");
        }
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        DocumentDerivationDescriptorProvider.Descriptor descriptor =
                descriptor(document);
        var active = jobRepository.findCurrentActive(
                document.getId(),
                profile.id(),
                document.getContentHash(),
                descriptor.documentKind(),
                descriptor.chunkerVersion());
        if (active.isPresent()) {
            EmbeddingJob current = active.get();
            EmbeddingJobRepository.CreateResult updated =
                    jobRepository.createOrCoalesce(
                            current.batchId(),
                            document.getId(),
                            profile.id(),
                            document.getContentHash(),
                            document.getVersion() != null
                                    ? document.getVersion() : 0L,
                            force,
                            current.maxAttempts(),
                            origin,
                            ChatPrincipal.fromCurrentRequest().id(),
                            current.requestGeneration(),
                            descriptor.documentKind(),
                            descriptor.chunkerVersion());
            if (force) {
                jobRepository.updateDocumentProcessing(
                        document.getId(), "PENDING", null);
            }
            return new Result(
                    EmbeddingAction.ASYNC_COALESCED,
                    updated.job().status().name(),
                    profile.profileKey(),
                    updated.job().id(),
                    updated.job().batchId(),
                    null);
        }
        boolean fresh = documentEmbedService.hasFreshEmbedding(document);
        if (!contentChanged && !force && fresh) {
            return Result.skipped(profile.profileKey());
        }
        long generation = jobRepository.allocateGeneration(
                document.getId(),
                profile.id(),
                document.getContentHash(),
                descriptor.chunkerVersion(),
                force && fresh);
        jobRepository.cancelSuperseded(
                document.getId(), profile.id(), generation);
        EmbeddingJobRepository.CreateResult created =
                jobRepository.createOrCoalesce(
                        UUID.randomUUID(),
                        document.getId(),
                        profile.id(),
                        document.getContentHash(),
                        document.getVersion() != null ? document.getVersion() : 0L,
                        force,
                        jobProperties.getDefaultMaxAttempts(),
                        origin,
                        ChatPrincipal.fromCurrentRequest().id(),
                        generation,
                        descriptor.documentKind(),
                        descriptor.chunkerVersion());
        jobRepository.activateJob(
                document.getId(), profile.id(), generation, created.job().id());
        jobRepository.updateDocumentProcessing(
                document.getId(), "PENDING", null);
        return new Result(
                created.coalesced()
                        ? EmbeddingAction.ASYNC_COALESCED
                        : EmbeddingAction.ASYNC_QUEUED,
                "QUEUED",
                profile.profileKey(),
                created.job().id(),
                created.job().batchId(),
                null);
    }

    @Transactional
    public Result markNotRequestedInCurrentTransaction(RagDocument document) {
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        DocumentDerivationDescriptorProvider.Descriptor descriptor =
                descriptor(document);
        jobRepository.markNotRequested(
                document.getId(),
                profile.id(),
                document.getContentHash(),
                descriptor.chunkerVersion());
        jobRepository.updateDocumentProcessing(
                document.getId(), "NOT_REQUESTED", null);
        return Result.skipped(profile.profileKey());
    }

    @Transactional
    public int cancelActiveInCurrentTransaction(long documentId) {
        return jobRepository.cancelActiveForDocument(documentId);
    }

    public Result completeAfterCommit(Result queued) {
        if (queued == null || queued.embeddingJobId() == null) {
            return queued;
        }
        if (jobExecutor == null) {
            return queued;
        }
        EmbeddingJob completed = jobExecutor.executeNow(queued.embeddingJobId());
        return switch (completed.status()) {
            case SUCCEEDED -> new Result(
                    EmbeddingAction.SYNC_COMPLETED,
                    "COMPLETED",
                    queued.embeddingProfileKey(),
                    completed.id(),
                    completed.batchId(),
                    null);
            case FAILED, CANCELLED, STALE -> new Result(
                    EmbeddingAction.SYNC_COMPLETED,
                    "FAILED",
                    queued.embeddingProfileKey(),
                    completed.id(),
                    completed.batchId(),
                    completed.lastError());
            case QUEUED, RUNNING -> queued;
        };
    }

    private DocumentDerivationDescriptorProvider.Descriptor descriptor(
            RagDocument document) {
        if (descriptorProvider != null) {
            return descriptorProvider.describe(document);
        }
        return RagDocument.JSON_RECORD.equals(document.getDocumentType())
                ? new DocumentDerivationDescriptorProvider.Descriptor(
                        "JSON_RECORD", "json-record-v1:single")
                : new DocumentDerivationDescriptorProvider.Descriptor(
                        "TEXT", "legacy-compatible");
    }

    public record Result(
            EmbeddingAction action,
            String embeddingStatus,
            String embeddingProfileKey,
            UUID embeddingJobId,
            UUID embeddingBatchId,
            String error) {

        static Result skipped(String profileKey) {
            return new Result(
                    EmbeddingAction.SKIPPED, "NOT_REQUESTED",
                    profileKey, null, null, null);
        }
    }
}
