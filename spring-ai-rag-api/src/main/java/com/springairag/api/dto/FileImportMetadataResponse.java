package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Readable metadata for a UUID-backed file import")
public record FileImportMetadataResponse(
        @Schema(description = "Stable import UUID")
        String importId,
        @Schema(description = "Import source type", example = "PDF")
        String sourceType,
        @Schema(description = "Normalized original upload filename")
        String originalFilename,
        @Schema(description = "Human-readable display name")
        String displayName,
        @Schema(description = "Entry Markdown path")
        String entryPath,
        @Schema(description = "Original binary path")
        String originalPath,
        @Schema(description = "Number of stored files")
        int fileCount,
        @Schema(description = "Import creation time")
        OffsetDateTime createdAt
) {
}
