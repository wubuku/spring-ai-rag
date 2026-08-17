package com.springairag.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 单个 embedding job 的无敏感内容摘要。
 */
public record EmbeddingJobResponse(
        UUID id,
        UUID batchId,
        Long documentId,
        Long embeddingProfileId,
        boolean force,
        String contentHash,
        long documentVersion,
        String status,
        int attemptCount,
        int maxAttempts,
        OffsetDateTime availableAt,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime cancelRequestedAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime updatedAt,
        boolean coalesced) {
}
