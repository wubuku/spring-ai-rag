package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Result of an idempotent source deletion.
 */
@Schema(description = "External document source deletion result")
public record ExternalDocumentDeleteResponse(
        Long documentId,
        String collectionKey,
        String externalId,
        String sourceRevision,
        String action,
        int versionNumber,
        boolean enabled,
        LocalDateTime sourceDeletedAt,
        String errorCode,
        String error
) {
}
