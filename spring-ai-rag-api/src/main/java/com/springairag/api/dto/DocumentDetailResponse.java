package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Full document detail response (single document GET endpoint).
 */
@Schema(description = "Document detail response")
public record DocumentDetailResponse(
        @Schema(description = "Document ID", example = "1")
        Long id,

        @Schema(description = "Document title", example = "Introduction to RAG")
        String title,

        @Schema(description = "Document source URL", example = "https://example.com/doc.pdf")
        String source,

        @Schema(description = "Document type", example = "PDF")
        String documentType,

        @Schema(description = "Processing status", example = "COMPLETED")
        String processingStatus,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt,

        @Schema(description = "Document size in bytes", example = "4096")
        Long size,

        @Schema(description = "Content hash for cache validation")
        String contentHash,

        @Schema(description = "Whether the document is enabled", example = "true")
        boolean enabled,

        @Schema(description = "Associated collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Associated collection name", example = "My Knowledge Base")
        String collectionName,

        @Schema(description = "Number of embedding chunks", example = "5")
        long chunkCount,

        @Schema(description = "Full content")
        String content,

        @Schema(description = "Additional metadata")
        Map<String, Object> metadata
) {
}
