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
        String chunkerVersion) {

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
                0L, null, null);
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
                requestedByPrincipalId, 0L, null, null);
    }
}
