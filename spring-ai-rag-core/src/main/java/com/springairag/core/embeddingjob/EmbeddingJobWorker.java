package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagEmbeddingJobProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingCommitRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 有界轮询、租约化的 embedding job worker。
 */
@Component
@ConditionalOnProperty(
        prefix = "rag.embedding-jobs",
        name = "enabled",
        havingValue = "true")
public class EmbeddingJobWorker {

    private static final Logger log =
            LoggerFactory.getLogger(EmbeddingJobWorker.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final EmbeddingJobRepository repository;
    private final DocumentEmbedService documentEmbedService;
    private final EmbeddingProfileProvider profileProvider;
    private final RagEmbeddingJobProperties properties;
    private final String workerId = "embed-" + UUID.randomUUID()
            .toString().replace("-", "").substring(0, 24);

    public EmbeddingJobWorker(
            EmbeddingJobRepository repository,
            DocumentEmbedService documentEmbedService,
            EmbeddingProfileProvider profileProvider,
            RagProperties properties) {
        this.repository = repository;
        this.documentEmbedService = documentEmbedService;
        this.profileProvider = profileProvider;
        this.properties = properties.getEmbeddingJobs();
    }

    @Scheduled(
            fixedDelayString = "${rag.embedding-jobs.poll-interval-ms:1000}")
    public void poll() {
        for (EmbeddingJob job : repository.claim(
                workerId,
                properties.getClaimBatchSize(),
                properties.getLeaseSeconds())) {
            process(job);
        }
    }

    void process(EmbeddingJob job) {
        try {
            long activeProfileId =
                    profileProvider.getActiveProfile().id();
            if (activeProfileId != job.embeddingProfileId()
                    || !repository.isCommitAllowed(
                    job.id(), workerId, activeProfileId)) {
                terminalWithoutCommit(job, "Embedding job snapshot is stale");
                return;
            }

            boolean force = repository.find(job.id())
                    .map(EmbeddingJob::force)
                    .orElse(job.force());
            Map<String, Object> result = embed(job, force);
            finishAttempt(job, force, result);
        } catch (EmbeddingCommitRejectedException e) {
            terminalWithoutCommit(job, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Embedding job {} attempt failed: {}",
                    job.id(), safeError(e.getMessage()));
            repository.markFailure(
                    job.id(),
                    workerId,
                    safeError(e.getMessage()),
                    properties.getRetryBackoffSeconds());
        }
    }

    private Map<String, Object> embed(
            EmbeddingJob job,
            boolean force) {
        return documentEmbedService.embedDocumentForJob(
                job.documentId(),
                force,
                () -> {
                    long currentProfileId =
                            profileProvider.getActiveProfile().id();
                    if (!repository.isCommitAllowed(
                            job.id(),
                            workerId,
                            currentProfileId)) {
                        throw new EmbeddingCommitRejectedException(
                                "Embedding job lost commit eligibility");
                    }
                });
    }

    private void finishAttempt(
            EmbeddingJob job,
            boolean force,
            Map<String, Object> result) {
            String status = String.valueOf(
                    result.getOrDefault("status", "FAILED"));
        if ("COMPLETED".equals(status)) {
            repository.markSucceeded(job.id(), workerId, true);
            return;
        }
        if ("CACHED".equals(status)) {
            if (repository.markSucceeded(job.id(), workerId, force) > 0) {
                return;
            }
            EmbeddingJob current = repository.find(job.id()).orElse(job);
            long activeProfileId = profileProvider.getActiveProfile().id();
            if (!force
                    && current.force()
                    && repository.isCommitAllowed(
                    job.id(), workerId, activeProfileId)) {
                finishAttempt(job, true, embed(job, true));
                return;
            }
            terminalWithoutCommit(
                    job, "Embedding job lost completion eligibility");
            return;
        }
        repository.markFailure(
                job.id(),
                workerId,
                safeError(String.valueOf(result.getOrDefault(
                        "error", "Embedding provider failed"))),
                properties.getRetryBackoffSeconds());
    }

    private void terminalWithoutCommit(
            EmbeddingJob job, String staleReason) {
        if (repository.isCancellationRequested(job.id())) {
            repository.markCancelled(job.id(), workerId);
        } else {
            repository.markStale(
                    job.id(), workerId, safeError(staleReason));
        }
    }

    private String safeError(String value) {
        String raw = value == null || value.isBlank()
                ? "Embedding job failed"
                : value;
        String masked = SensitiveDataMaskingConverter.maskSensitiveData(raw);
        return masked.length() <= MAX_ERROR_LENGTH
                ? masked
                : masked.substring(0, MAX_ERROR_LENGTH);
    }
}
