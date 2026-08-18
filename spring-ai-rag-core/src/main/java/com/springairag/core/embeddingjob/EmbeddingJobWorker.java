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

import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
            .toString().replace("-", "").substring(0, 16);
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeats;

    public EmbeddingJobWorker(
            EmbeddingJobRepository repository,
            DocumentEmbedService documentEmbedService,
            EmbeddingProfileProvider profileProvider,
            RagProperties properties) {
        this.repository = repository;
        this.documentEmbedService = documentEmbedService;
        this.profileProvider = profileProvider;
        this.properties = properties.getEmbeddingJobs();
        int concurrency = this.properties.getWorkerConcurrency();
        this.slots = new Semaphore(concurrency);
        this.workers = Executors.newFixedThreadPool(concurrency, r -> {
            Thread thread = new Thread(r, "embedding-job-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "embedding-job-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(
            fixedDelayString = "${rag.embedding-jobs.poll-interval-ms:1000}")
    public void poll() {
        int claimLimit = Math.min(
                properties.getClaimBatchSize(), slots.availablePermits());
        for (int i = 0; i < claimLimit; i++) {
            if (!slots.tryAcquire()) {
                break;
            }
            String leaseOwner = nextLeaseOwner();
            java.util.List<EmbeddingJob> claimed;
            try {
                claimed = repository.claim(
                        leaseOwner, 1, properties.getLeaseSeconds());
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (claimed.isEmpty()) {
                slots.release();
                break;
            }
            EmbeddingJob job = claimed.getFirst();
            workers.submit(() -> {
                try {
                    process(job, leaseOwner);
                } finally {
                    slots.release();
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdown();
        heartbeats.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    void process(EmbeddingJob job) {
        String leaseOwner = job.leaseOwner() == null
                ? nextLeaseOwner()
                : job.leaseOwner();
        process(job, leaseOwner);
    }

    private void process(EmbeddingJob job, String leaseOwner) {
        repository.markProgress(job.id(), leaseOwner, "CLAIMED");
        int heartbeatSeconds = Math.max(10, properties.getLeaseSeconds() / 3);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> repository.heartbeat(
                        job.id(), leaseOwner, properties.getLeaseSeconds()),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS);
        try {
            long activeProfileId =
                    profileProvider.getActiveProfile().id();
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
            log.warn("Embedding job {} attempt failed: {}",
                    job.id(), safeError(e.getMessage()));
            repository.markFailure(
                    job.id(),
                    leaseOwner,
                    safeError(e.getMessage()),
                    properties.getRetryBackoffSeconds());
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
        String status = String.valueOf(
                result.getOrDefault("status", "FAILED"));
        if ("COMPLETED".equals(status)) {
            if (repository.markSucceeded(job.id(), leaseOwner, true) == 0) {
                terminalWithoutCommit(
                        job, leaseOwner,
                        "Embedding job lost completion eligibility");
            }
            return;
        }
        if ("CACHED".equals(status)) {
            if (repository.markSucceeded(job.id(), leaseOwner, force) > 0) {
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
    }

    private String nextLeaseOwner() {
        return workerId + "-"
                + UUID.randomUUID().toString().replace("-", "");
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
