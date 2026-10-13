package com.springairag.core.embeddingjob;

import com.springairag.api.dto.EmbedProgressEvent;
import com.springairag.api.enums.DocumentProcessingStageName;
import com.springairag.api.enums.DocumentProcessingStageStatus;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagEmbeddingJobProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.documentprocessing.DocumentProcessingRunRepository;
import com.springairag.core.documentprocessing.DocumentProcessingRunService;
import com.springairag.core.logging.SensitiveDataMaskingConverter;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingCommitGuard;
import com.springairag.core.service.EmbeddingCommitRejectedException;
import com.springairag.core.exception.DocumentNotFoundException;
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
    private DocumentProcessingRunRepository processingRepository; // optional-claim: 兼容不装配 V61 账本的旧测试构造路径
    private DocumentProcessingRunService processingRunService; // optional-claim: 兼容不装配 V61 账本的旧测试构造路径
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProcessingRepository(
            DocumentProcessingRunRepository processingRepository) {
        this.processingRepository = processingRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProcessingRunService(
            DocumentProcessingRunService processingRunService) {
        this.processingRunService = processingRunService;
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
        ensureProcessingRun(job);
        if (processingRepository != null) {
            processingRepository.mirrorClaimed(job, leaseOwner);
        }
        repository.markProgress(job.id(), leaseOwner, "CLAIMED");
        int heartbeatSeconds = Math.max(10, properties.getLeaseSeconds() / 3);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> {
                    repository.heartbeat(
                            job.id(), leaseOwner, properties.getLeaseSeconds());
                    if (processingRepository != null) {
                        processingRepository.heartbeat(job, leaseOwner);
                    }
                },
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
            markStage(job, leaseOwner, DocumentProcessingStageName.PARSING,
                    DocumentProcessingStageStatus.RUNNING, null, null);
            Map<String, Object> result = embed(job, force, leaseOwner);
            repository.markProgress(job.id(), leaseOwner, "COMMITTING");
            finishAttempt(job, force, result, leaseOwner);
        } catch (EmbeddingCommitRejectedException e) {
            terminalWithoutCommit(job, leaseOwner, e.getMessage());
        } catch (RuntimeException e) {
            String error = safeError(e.getMessage());
            boolean nonRetryable = isNonRetryableException(e);
            markCurrentStageFailed(job, leaseOwner,
                    nonRetryable ? "NON_RETRYABLE" : "EMBEDDING_FAILED", error);
            if (nonRetryable) {
                repository.markNonRetryableFailure(job.id(), leaseOwner, error);
            } else {
                repository.markFailure(
                        job.id(), leaseOwner, error,
                        properties.getRetryBackoffSeconds());
            }
            repository.refreshStateFromJob(job.id());
            mirrorCurrentJob(job.id());
        } finally {
            heartbeat.cancel(true);
        }
    }

    private Map<String, Object> embed(
            EmbeddingJob job,
            boolean force,
            String leaseOwner) {
        EmbeddingCommitGuard guard = () -> {
            long currentProfileId = profileProvider.getActiveProfile().id();
            if (!repository.claimCommitAllowed(
                    job.id(), leaseOwner, currentProfileId,
                    properties.getLeaseSeconds())) {
                throw new EmbeddingCommitRejectedException(
                        "Embedding job lost commit eligibility");
            }
        };
        if (processingRepository == null && processingRunService == null) {
            return documentEmbedService.embedDocumentForJob(
                    job.documentId(), force, guard);
        }
        return documentEmbedService.embedDocumentForJob(
                job.documentId(), force, guard,
                event -> onProgress(job, leaseOwner, event));
    }

    private void finishAttempt(
            EmbeddingJob job,
            boolean force,
            Map<String, Object> result,
            String leaseOwner) {
        String status = String.valueOf(result.getOrDefault("status", "FAILED"));
        if ("COMPLETED".equals(status)) {
            markStage(job, leaseOwner, DocumentProcessingStageName.FINALIZING,
                    DocumentProcessingStageStatus.SUCCEEDED, null, null);
            if (repository.markSucceeded(job.id(), leaseOwner, true) == 0) {
                terminalWithoutCommit(
                        job, leaseOwner,
                        "Embedding job lost completion eligibility");
            } else {
                repository.refreshStateFromJob(job.id());
                mirrorCurrentJob(job.id());
            }
            return;
        }
        if ("CACHED".equals(status)) {
            markStage(job, leaseOwner, DocumentProcessingStageName.FINALIZING,
                    DocumentProcessingStageStatus.SUCCEEDED, null, null);
            if (repository.markSucceeded(job.id(), leaseOwner, force) > 0) {
                repository.refreshStateFromJob(job.id());
                mirrorCurrentJob(job.id());
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
        String error = safeError(String.valueOf(result.getOrDefault(
                "error", "Embedding provider failed")));
        boolean nonRetryable = isNonRetryableResult(error);
        markCurrentStageFailed(job, leaseOwner,
                nonRetryable ? "NON_RETRYABLE" : "EMBEDDING_FAILED", error);
        if (nonRetryable) {
            repository.markNonRetryableFailure(job.id(), leaseOwner, error);
        } else {
            repository.markFailure(
                    job.id(), leaseOwner, error,
                    properties.getRetryBackoffSeconds());
        }
        repository.refreshStateFromJob(job.id());
        mirrorCurrentJob(job.id());
    }

    private void terminalWithoutCommit(
            EmbeddingJob job,
            String leaseOwner,
            String staleReason) {
        boolean processingLedgerEnabled = processingRepository != null
                || processingRunService != null;
        if (!processingLedgerEnabled) {
            if (repository.isCancellationRequested(job.id())) {
                repository.markCancelled(job.id(), leaseOwner);
            } else {
                repository.markStale(
                        job.id(), leaseOwner, safeError(staleReason));
            }
            repository.refreshStateFromJob(job.id());
            mirrorCurrentJob(job.id());
            return;
        }

        if (!repository.isLeaseOwnedAndLive(job.id(), leaseOwner)) {
            // 仅丢失 lease/owner 不属于 stale；让既有 reclaim 路径接管。
            return;
        }
        if (!repository.isCurrentActiveJob(job.id())) {
            repository.markStale(
                    job.id(), leaseOwner, safeError(staleReason));
        } else if (repository.isCancellationRequested(job.id())) {
            repository.markCancelled(job.id(), leaseOwner);
        } else {
            // 当前 generation 仍 active，但快照/profile/chunker 已不再可提交。
            repository.markStale(
                    job.id(), leaseOwner, safeError(staleReason));
        }
        repository.refreshStateFromJob(job.id());
        mirrorCurrentJob(job.id());
    }

    private void ensureProcessingRun(EmbeddingJob job) {
        if (processingRunService != null) {
            processingRunService.ensureForJob(job, job.origin(), null, true);
        }
    }

    private void onProgress(
            EmbeddingJob job,
            String leaseOwner,
            EmbedProgressEvent event) {
        DocumentProcessingStageName stage = switch (event.phase()) {
            case "PREPARING" -> DocumentProcessingStageName.PARSING;
            case "CHUNKING" -> DocumentProcessingStageName.CHUNKING;
            case "EMBEDDING" -> DocumentProcessingStageName.EMBEDDING;
            case "STORING" -> DocumentProcessingStageName.INDEXING;
            case "COMPLETED" -> DocumentProcessingStageName.FINALIZING;
            default -> null;
        };
        if (stage == null) {
            return;
        }
        if ("CHUNKING".equals(event.phase())) {
            markStage(job, leaseOwner, DocumentProcessingStageName.PARSING,
                    DocumentProcessingStageStatus.SUCCEEDED, null, null);
        } else if ("EMBEDDING".equals(event.phase())) {
            markStage(job, leaseOwner, DocumentProcessingStageName.CHUNKING,
                    DocumentProcessingStageStatus.SUCCEEDED, null, null);
        } else if ("STORING".equals(event.phase())) {
            markStage(job, leaseOwner, DocumentProcessingStageName.EMBEDDING,
                    DocumentProcessingStageStatus.SUCCEEDED, null, null);
        } else if ("COMPLETED".equals(event.phase())) {
            markStage(job, leaseOwner, DocumentProcessingStageName.INDEXING,
                    DocumentProcessingStageStatus.SUCCEEDED, null, null);
            markStage(job, leaseOwner, DocumentProcessingStageName.FINALIZING,
                    DocumentProcessingStageStatus.RUNNING, null, null);
        }
        markStage(job, leaseOwner, stage,
                DocumentProcessingStageStatus.RUNNING, null, null);
        if (processingRepository != null) {
            processingRepository.updateStageProgress(
                    job, leaseOwner, stage, event.current(), event.total(),
                    event.phase());
        }
    }

    private void markStage(
            EmbeddingJob job,
            String leaseOwner,
            DocumentProcessingStageName stage,
            DocumentProcessingStageStatus status,
            String errorCode,
            String error) {
        if (processingRepository != null) {
            processingRepository.markStage(
                    job, leaseOwner, stage, status, errorCode, error);
        }
    }

    private void markCurrentStageFailed(
            EmbeddingJob job,
            String leaseOwner,
            String errorCode,
            String error) {
        if (processingRepository != null) {
            processingRepository.markCurrentStageFailed(
                    job, leaseOwner, errorCode, error);
        }
    }

    private boolean isNonRetryableException(RuntimeException error) {
        return error instanceof DocumentNotFoundException
                || error instanceof IllegalArgumentException;
    }

    private boolean isNonRetryableResult(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        String normalized = error.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("document content is empty")
                || normalized.contains("produced no chunks")
                || normalized.contains("result count mismatch")
                || normalized.contains("response order mismatch")
                || normalized.contains("embedding is null")
                || normalized.contains("dimension mismatch")
                || normalized.contains("non-finite value")
                || normalized.contains("unsupported document")
                || normalized.contains("unsupported chunker");
    }

    private void mirrorCurrentJob(UUID jobId) {
        if (processingRepository != null) {
            repository.find(jobId).ifPresent(processingRepository::mirrorJob);
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

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
    }
}
