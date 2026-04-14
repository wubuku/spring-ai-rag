package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Document version response (single version detail or list item).
 */
@Schema(description = "Document version response")
public record DocumentVersionResponse(
        @Schema(description = "Version record ID", example = "1")
        Long id,

        @Schema(description = "Parent document ID", example = "42")
        Long documentId,

        @Schema(description = "Version number (incremental)", example = "3")
        int versionNumber,

        @Schema(description = "Content hash", example = "sha256:abc123...")
        String contentHash,

        @Schema(description = "Size in bytes", example = "4096")
        Long size,

        @Schema(description = "Change type: CREATED, UPDATED, DELETED", example = "UPDATED")
        String changeType,

        @Schema(description = "Human-readable change description", example = "Content updated via batch import")
        String changeDescription,

        @Schema(description = "Version creation timestamp")
        LocalDateTime createdAt,

        @Schema(description = "Content snapshot (only in single version detail, omitted in list)")
        String contentSnapshot
) {
}
