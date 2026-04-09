package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of re-embedding a single document")
public record ReembedResultResponse(
        @Schema(description = "Document ID") Long documentId,
        @Schema(description = "Document title") String title,
        @Schema(description = "Re-embedding status (COMPLETED or error)") String status,
        @Schema(description = "Number of chunks created") Integer chunks,
        @Schema(description = "Status message or error details") String message
) {
}
