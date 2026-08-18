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
import org.springframework.stereotype.Service;

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

    public EmbeddingDispatchService(
            DocumentEmbedService documentEmbedService,
            EmbeddingJobRepository jobRepository,
            EmbeddingProfileProvider profileProvider,
            RagProperties ragProperties) {
        this.documentEmbedService = documentEmbedService;
        this.jobRepository = jobRepository;
        this.profileProvider = profileProvider;
        this.jobProperties = ragProperties.getEmbeddingJobs();
    }

    public Result dispatchAfterCommit(
            RagDocument document,
            EmbeddingPolicy policy,
            boolean contentChanged,
            String origin) {
        EmbeddingProfile profile = profileProvider.getActiveProfile();
        if (policy == EmbeddingPolicy.SKIP) {
            return Result.skipped(profile.profileKey());
        }
        if (policy == EmbeddingPolicy.ASYNC) {
            throw new IllegalStateException(
                    "ASYNC jobs must be enqueued in the document transaction");
        }
        boolean fresh = documentEmbedService.hasFreshEmbedding(document);
        if (!contentChanged && fresh) {
            return new Result(
                    EmbeddingAction.SYNC_CACHED, "CACHED",
                    profile.profileKey(), null, null, null);
        }
        try {
            Map<String, Object> embed = documentEmbedService.embedDocument(
                    document.getId(), false);
            String status = String.valueOf(embed.getOrDefault("status", "FAILED"));
            String profileKey = String.valueOf(embed.getOrDefault(
                    "embeddingProfileKey", profile.profileKey()));
            if ("CACHED".equals(status)) {
                return new Result(
                        EmbeddingAction.SYNC_CACHED, "CACHED",
                        profileKey, null, null, null);
            }
            if ("COMPLETED".equals(status)) {
                return new Result(
                        EmbeddingAction.SYNC_COMPLETED, "COMPLETED",
                        profileKey, null, null, null);
            }
            return new Result(
                    EmbeddingAction.SYNC_COMPLETED, "FAILED",
                    profileKey, null, null,
                    embed.get("error") == null ? null : String.valueOf(embed.get("error")));
        } catch (RuntimeException e) {
            return new Result(
                    EmbeddingAction.SYNC_COMPLETED, "FAILED",
                    profile.profileKey(), null, null, e.getMessage());
        }
    }

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
        boolean fresh = documentEmbedService.hasFreshEmbedding(document);
        if (!contentChanged && !force && fresh) {
            var active = jobRepository.findActive(
                    document.getId(), profile.id(), document.getContentHash());
            if (active.isPresent()) {
                EmbeddingJobRepository.CreateResult updated =
                        jobRepository.createOrCoalesce(
                                active.get().batchId(),
                                document.getId(),
                                profile.id(),
                                document.getContentHash(),
                                document.getVersion() != null ? document.getVersion() : 0L,
                                false,
                                active.get().maxAttempts(),
                                origin,
                                ChatPrincipal.fromCurrentRequest().id());
                return new Result(
                        EmbeddingAction.ASYNC_COALESCED,
                        "QUEUED",
                        profile.profileKey(),
                        updated.job().id(),
                        updated.job().batchId(),
                        null);
            }
            return Result.skipped(profile.profileKey());
        }
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
                        ChatPrincipal.fromCurrentRequest().id());
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
