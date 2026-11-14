package com.springairag.core.embeddingjob;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * embedding job 数据库快照。
 */
public record EmbeddingJob(
        UUID id,
        UUID batchId,
        long documentId,
        long embeddingProfileId,
        boolean force,
        String contentHash,
        long documentVersion,
        EmbeddingJobStatus status,
        int attemptCount,
        int maxAttempts,
        OffsetDateTime availableAt,
        String leaseOwner,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime cancelRequestedAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime updatedAt,
        String origin,
        String requestedByPrincipalId,
        long requestGeneration,
        String documentKind,
        String chunkerVersion,
        String terminalReason,
        String tenantId) {

    public EmbeddingJob(
            UUID id,
            UUID batchId,
            long documentId,
            long embeddingProfileId,
            boolean force,
            String contentHash,
            long documentVersion,
            EmbeddingJobStatus status,
            int attemptCount,
            int maxAttempts,
            OffsetDateTime availableAt,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime cancelRequestedAt,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt) {
        this(id, batchId, documentId, embeddingProfileId, force, contentHash,
                documentVersion, status, attemptCount, maxAttempts, availableAt,
                leaseOwner, leaseExpiresAt, cancelRequestedAt, lastError,
                createdAt, startedAt, finishedAt, updatedAt, null, null,
                0L, null, null, null);
    }

    public EmbeddingJob(
            UUID id,
            UUID batchId,
            long documentId,
            long embeddingProfileId,
            boolean force,
            String contentHash,
            long documentVersion,
            EmbeddingJobStatus status,
            int attemptCount,
            int maxAttempts,
            OffsetDateTime availableAt,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime cancelRequestedAt,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt,
            String origin,
            String requestedByPrincipalId) {
        this(id, batchId, documentId, embeddingProfileId, force, contentHash,
                documentVersion, status, attemptCount, maxAttempts, availableAt,
                leaseOwner, leaseExpiresAt, cancelRequestedAt, lastError,
                createdAt, startedAt, finishedAt, updatedAt, origin,
                requestedByPrincipalId, 0L, null, null, null);
    }

    public EmbeddingJob(
            UUID id,
            UUID batchId,
            long documentId,
            long embeddingProfileId,
            boolean force,
            String contentHash,
            long documentVersion,
            EmbeddingJobStatus status,
            int attemptCount,
            int maxAttempts,
            OffsetDateTime availableAt,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime cancelRequestedAt,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt,
            String origin,
            String requestedByPrincipalId,
            long requestGeneration,
            String documentKind,
            String chunkerVersion) {
        this(id, batchId, documentId, embeddingProfileId, force, contentHash,
                documentVersion, status, attemptCount, maxAttempts, availableAt,
                leaseOwner, leaseExpiresAt, cancelRequestedAt, lastError,
                createdAt, startedAt, finishedAt, updatedAt, origin,
                requestedByPrincipalId, requestGeneration, documentKind,
                chunkerVersion, null, null);
    }

    /** WP8 兼容构造器：旧调用点还不知道 tenant 时，由 repository 投影补齐。 */
    public EmbeddingJob(
            UUID id,
            UUID batchId,
            long documentId,
            long embeddingProfileId,
            boolean force,
            String contentHash,
            long documentVersion,
            EmbeddingJobStatus status,
            int attemptCount,
            int maxAttempts,
            OffsetDateTime availableAt,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime cancelRequestedAt,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt,
            String origin,
            String requestedByPrincipalId,
            long requestGeneration,
            String documentKind,
            String chunkerVersion,
            String terminalReason) {
        this(id, batchId, documentId, embeddingProfileId, force, contentHash,
                documentVersion, status, attemptCount, maxAttempts, availableAt,
                leaseOwner, leaseExpiresAt, cancelRequestedAt, lastError,
                createdAt, startedAt, finishedAt, updatedAt, origin,
                requestedByPrincipalId, requestGeneration, documentKind,
                chunkerVersion, terminalReason, null);
    }

    /** 26 参 canonical：tenantId 来自 claim 行（rag_embedding_jobs.tenant_id）。 */
    public EmbeddingJob(
            UUID id,
            UUID batchId,
            long documentId,
            long embeddingProfileId,
            boolean force,
            String contentHash,
            long documentVersion,
            EmbeddingJobStatus status,
            int attemptCount,
            int maxAttempts,
            OffsetDateTime availableAt,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime cancelRequestedAt,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt,
            String origin,
            String requestedByPrincipalId,
            long requestGeneration,
            String documentKind,
            String chunkerVersion,
            String terminalReason,
            String tenantId) {
        this.id = id;
        this.batchId = batchId;
        this.documentId = documentId;
        this.embeddingProfileId = embeddingProfileId;
        this.force = force;
        this.contentHash = contentHash;
        this.documentVersion = documentVersion;
        this.status = status;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.availableAt = availableAt;
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.cancelRequestedAt = cancelRequestedAt;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.updatedAt = updatedAt;
        this.origin = origin;
        this.requestedByPrincipalId = requestedByPrincipalId;
        this.requestGeneration = requestGeneration;
        this.documentKind = documentKind;
        this.chunkerVersion = chunkerVersion;
        this.terminalReason = terminalReason;
        this.tenantId = tenantId;
    }
}
