package com.springairag.api.dto;

import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.api.enums.DocumentProcessingRunOrigin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 文档处理运行的完整公开快照。 */
public record DocumentProcessingRunResponse(
        UUID runId,
        Long documentId,
        Long embeddingProfileId,
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
        OffsetDateTime cancelRequestedAt,
        OffsetDateTime deadLetteredAt,
        String deadLetterReason,
        String lastErrorCode,
        String lastError,
        DocumentProcessingRunOrigin origin,
        UUID supersedesRunId,
        boolean legacyBridge,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime updatedAt,
        List<DocumentProcessingStageResponse> stages,
        Boolean coalesced) {
}
