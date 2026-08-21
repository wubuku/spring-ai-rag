package com.springairag.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 可重放的派生修复 operation 状态。 */
public record DerivationRepairStatusResponse(
        UUID repairId,
        String collectionKey,
        String status,
        Instant createdAt,
        Instant completedAt,
        List<Item> items
) {
    public record Item(
            long documentId,
            String action,
            String status,
            String localActionStatus,
            String vectorActionStatus,
            UUID embeddingJobId,
            String resultCode,
            String error
    ) {
    }
}
