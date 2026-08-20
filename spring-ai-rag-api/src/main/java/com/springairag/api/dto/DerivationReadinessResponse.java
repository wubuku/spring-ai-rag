package com.springairag.api.dto;

import java.time.Instant;

/** Collection 派生完整性摘要。 */
public record DerivationReadinessResponse(
        String collectionKey,
        String activeEmbeddingProfileKey,
        long enabledDocuments,
        long readyDocuments,
        long keywordOnlyDocuments,
        long indexingDocuments,
        long localUnavailableDocuments,
        long vectorRepairNeededDocuments,
        long notRequestedDocuments,
        long corruptDocuments,
        long disabledDocuments,
        Instant scannedAt
) {
}
