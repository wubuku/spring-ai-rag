package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Document summary within collection context
 */
@Schema(description = "Document summary in collection context")
public record DocumentSummary(
        @Schema(description = "Document ID", example = "1")
        Long id,

        @Schema(description = "Document title", example = "Introduction to RAG")
        String title,

        @Schema(description = "Document source", example = "https://example.com/doc.pdf")
        String source,

        @Schema(description = "Document type", example = "PDF")
        String documentType,

        @Schema(description = "Processing status", example = "COMPLETED")
        String processingStatus,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @Schema(description = "Document size in bytes", example = "4096")
        Long size
) {
}
