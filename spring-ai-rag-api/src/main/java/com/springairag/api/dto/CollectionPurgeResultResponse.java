package com.springairag.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Collection 清理完成后的最小可重放结果。
 */
public record CollectionPurgeResultResponse(
        UUID previewId,
        String status,
        Long collectionId,
        String collectionKey,
        long purgedDocumentCount,
        long purgedExternalDocumentCount,
        long purgedLocalDocumentCount,
        LocalDateTime deletedAt,
        LocalDateTime purgedAt,
        long collectionVersion) {
}
