package com.springairag.core.documentprocessing;

import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.api.enums.DocumentProcessingRunOrigin;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文档派生处理运行的数据库快照。 */
public record DocumentProcessingRun(
        UUID id,
        long documentId,
        long embeddingProfileId,
        UUID embeddingJobId,
        long requestGeneration,
        long documentVersion,
        String contentHash,
        String documentKind,
        String chunkerVersion,
        int attemptNo,
        int executionAttempt,
        int maxAttempts,
        DocumentProcessingRunStatus status,
        String activeStage,
        OffsetDateTime nextRetryAt,
        OffsetDateTime lastHeartbeatAt,
        OffsetDateTime cancelRequestedAt,
        OffsetDateTime deadLetteredAt,
        String deadLetterReason,
        String lastErrorCode,
        String lastError,
        DocumentProcessingRunOrigin origin,
        String requestedByPrincipalId,
        UUID supersedesRunId,
        boolean legacyBridge,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime updatedAt) {

    public boolean active() {
        return status == DocumentProcessingRunStatus.QUEUED
                || status == DocumentProcessingRunStatus.RUNNING;
    }
}
