package com.springairag.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 派生修复 preview 结果；token 只在本响应中出现。 */
public record DerivationRepairPreviewResponse(
        UUID repairId,
        String collectionKey,
        String previewFingerprint,
        String previewToken,
        Instant expiresAt,
        List<Item> items,
        Map<String, Long> actionCounts,
        long skippedDocuments
) {
    public record Item(long documentId, String action, String reasonCode) {
    }
}
