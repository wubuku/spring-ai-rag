package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 不包含正文的 Collection 清理预览。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionPurgePreviewResponse(
        UUID previewId,
        Long collectionId,
        String collectionKey,
        long collectionVersion,
        long chatCommitFenceVersion,
        String status,
        long documentCount,
        long externalDocumentCount,
        long localDocumentCount,
        long embeddingCount,
        long embeddingJobCount,
        long versionCount,
        long keywordChunkCount,
        long repairPreviewCount,
        long repairItemCount,
        long derivedRowCount,
        long documentIdempotencyOperationCount,
        long feedbackCount,
        long feedbackDocumentReferenceCount,
        long documentAuditCount,
        long collectionAuditCount,
        long relocationMarkerCount,
        long affectedChatSessionCount,
        long chatHistoryCount,
        long chatMemoryCount,
        long chatSummaryCount,
        long chatTurnOperationCount,
        long activeSyncRunCount,
        long activeDerivationRepairCount,
        long activeChatSessionCount,
        long unindexedChatReferenceCount,
        long unindexedFeedbackReferenceCount,
        String confirmationToken,
        String fingerprint,
        OffsetDateTime previewExpiresAt,
        OffsetDateTime operationExpiresAt) {
}
