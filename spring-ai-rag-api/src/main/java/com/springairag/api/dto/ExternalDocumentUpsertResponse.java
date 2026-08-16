package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Result of an external document upsert.
 */
@Schema(description = "External document upsert result")
public record ExternalDocumentUpsertResponse(
        Long documentId,
        String collectionKey,
        String externalId,
        String sourceRevision,
        String action,
        boolean contentChanged,
        int versionNumber,
        String embeddingStatus,
        String embeddingProfileKey,
        boolean embeddingFresh,
        String processingStatus,
        LocalDateTime sourceDeletedAt,
        String errorCode,
        String error
) {
}
