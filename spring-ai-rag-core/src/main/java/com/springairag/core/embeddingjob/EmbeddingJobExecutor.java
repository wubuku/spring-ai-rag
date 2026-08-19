package com.springairag.core.embeddingjob;

import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagEmbeddingJobProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingCommitRejectedException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Worker 和同步等待请求共用的持久化 embedding job 执行器。
 */
@Service
public class EmbeddingJobExecutor {

    private static final int MAX_ERROR_LENGTH = 500;

    private final EmbeddingJobRepository repository;
    private final DocumentEmbedService documentEmbedService;
    private final EmbeddingProfileProvider profileProvider;
    private final RagEmbeddingJobProperties properties;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "embedding-job-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public EmbeddingJobExecutor(
            EmbeddingJobRepository repository,
            DocumentEmbedService documentEmbedService,
            EmbeddingProfileProvider profileProvider,
            RagProperties properties) {
        this.repository = repository;
        this.documentEmbedService = documentEmbedService;
        this.profileProvider = profileProvider;
        this.properties = properties.getEmbeddingJobs();
    }

    public EmbeddingJob executeNow(UUID jobId) {
        String leaseOwner = "sync-" + UUID.randomUUID().toString().replace("-", "");
        return repository.claimById(
                        jobId, leaseOwner, properties.getLeaseSeconds())
                .map(job -> {
                    processClaimed(job, leaseOwner);
                    return repository.find(jobId).orElse(job);
                })
                .orElseGet(() -> repository.find(jobId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Embedding job not found: " + jobId)));
    }

    public void processClaimed(EmbeddingJob job, String leaseOwner) {
        repository.markProgress(job.id(), leaseOwner, "CLAIMED");
        int heartbeatSeconds = Math.max(10, properties.getLeaseSeconds() / 3);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> repository.heartbeat(
                        job.id(), leaseOwner, properties.getLeaseSeconds()),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS);
        try {
            long activeProfileId = profileProvider.getActiveProfile().id();
            if (activeProfileId != job.embeddingProfileId()
                    || !repository.isCommitAllowed(
                    job.id(), leaseOwner, activeProfileId)) {
                terminalWithoutCommit(
                        job, leaseOwner, "Embedding job snapshot is stale");
                return;
            }

            boolean force = repository.find(job.id())
                    .map(EmbeddingJob::force)
                    .orElse(job.force());
            repository.markProgress(job.id(), leaseOwner, "EMBEDDING");
            Map<String, Object> result = embed(job, force, leaseOwner);
            repository.markProgress(job.id(), leaseOwner, "COMMITTING");
            finishAttempt(job, force, result, leaseOwner);
        } catch (EmbeddingCommitRejectedException e) {
            terminalWithoutCommit(job, leaseOwner, e.getMessage());
        } catch (RuntimeException e) {
            repository.markFailure(
                    job.id(),
                    leaseOwner,
                    safeError(e.getMessage()),
                    properties.getRetryBackoffSeconds());
            repository.refreshStateFromJob(job.id());
        } finally {
            heartbeat.cancel(true);
        }
    }

    private Map<String, Object> embed(
            EmbeddingJob job,
            boolean force,
            String leaseOwner) {
        return documentEmbedService.embedDocumentForJob(
                job.documentId(),
                force,
                () -> {
                    long currentProfileId =
                            profileProvider.getActiveProfile().id();
                    if (!repository.claimCommitAllowed(
                            job.id(),
                            leaseOwner,
                            currentProfileId,
                            properties.getLeaseSeconds())) {
                        throw new EmbeddingCommitRejectedException(
                                "Embedding job lost commit eligibility");
                    }
                });
    }

    private void finishAttempt(
            EmbeddingJob job,
            boolean force,
            Map<String, Object> result,
            String leaseOwner) {
        String status = String.valueOf(result.getOrDefault("status", "FAILED"));
        if ("COMPLETED".equals(status)) {
            if (repository.markSucceeded(job.id(), leaseOwner, true) == 0) {
                terminalWithoutCommit(
                        job, leaseOwner,
                        "Embedding job lost completion eligibility");
            } else {
                repository.refreshStateFromJob(job.id());
            }
            return;
        }
        if ("CACHED".equals(status)) {
            if (repository.markSucceeded(job.id(), leaseOwner, force) > 0) {
                repository.refreshStateFromJob(job.id());
                return;
            }
            EmbeddingJob current = repository.find(job.id()).orElse(job);
            long activeProfileId = profileProvider.getActiveProfile().id();
            if (!force
                    && current.force()
                    && repository.isCommitAllowed(
                    job.id(), leaseOwner, activeProfileId)) {
                finishAttempt(
                        job, true, embed(job, true, leaseOwner), leaseOwner);
                return;
            }
            terminalWithoutCommit(
                    job, leaseOwner,
                    "Embedding job lost completion eligibility");
            return;
        }
        repository.markFailure(
                job.id(),
                leaseOwner,
                safeError(String.valueOf(result.getOrDefault(
                        "error", "Embedding provider failed"))),
                properties.getRetryBackoffSeconds());
        repository.refreshStateFromJob(job.id());
    }

    private void terminalWithoutCommit(
            EmbeddingJob job,
            String leaseOwner,
            String staleReason) {
        if (repository.isCancellationRequested(job.id())) {
            repository.markCancelled(job.id(), leaseOwner);
        } else {
            repository.markStale(
                    job.id(), leaseOwner, safeError(staleReason));
        }
        repository.refreshStateFromJob(job.id());
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

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
    }
}

