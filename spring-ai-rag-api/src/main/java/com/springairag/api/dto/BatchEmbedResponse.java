package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Batch embedding response (POST /batch/embed).
 */
@Schema(description = "Batch embedding response")
public record BatchEmbedResponse(
        @Schema(description = "Per-document embedding results")
        List<BatchEmbedResultItem> results,

        @Schema(description = "Batch summary")
        BatchEmbedSummary summary
) {
    @Schema(description = "Single document embedding result")
    public record BatchEmbedResultItem(
            @Schema(description = "Document ID", example = "1")
            Long documentId,

            @Schema(description = "Status: COMPLETED, CACHED, FAILED, NOT_FOUND, or SKIPPED",
                    example = "COMPLETED")
            String status,

            @Schema(description = "Number of chunks created (COMPLETED only)", example = "5")
            Integer chunksCreated,

            @Schema(description = "Number of embeddings stored", example = "5")
            Integer embeddingsStored,

            @Schema(description = "Error message (FAILED only)")
            String error,

            @Schema(description = "Reason for SKIPPED status")
            String reason
    ) {
    }

    @Schema(description = "Batch summary counts")
    public record BatchEmbedSummary(
            @Schema(description = "Total documents in batch", example = "10")
            int total,

            @Schema(description = "Successfully embedded", example = "5")
            int success,

            @Schema(description = "Cache hits (skipped re-embedding)", example = "3")
            int cached,

            @Schema(description = "Failed to embed", example = "1")
            int failed,

            @Schema(description = "Skipped (empty content or no chunking needed)", example = "1")
            int skipped
    ) {
    }
}
