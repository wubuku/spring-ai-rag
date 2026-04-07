package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Batch create and embed documents response
 */
@Schema(description = "Batch create and embed documents response")
public record BatchCreateAndEmbedResponse(
        @Schema(description = "Number of successfully created documents", example = "10")
        int created,

        @Schema(description = "Number of successfully embedded documents", example = "10")
        int embedded,

        @Schema(description = "Number of skipped documents (content unchanged)", example = "2")
        int skipped,

        @Schema(description = "Number of failed documents", example = "0")
        int failed,

        @Schema(description = "Details of each document create+embed result")
        List<DocumentResult> results
) {
    @Schema(description = "Single document result")
    public record DocumentResult(
            @Schema(description = "Document ID", example = "1")
            Long documentId,

            @Schema(description = "Document title", example = "Product Manual")
            String title,

            @Schema(description = "Whether successfully embedded", example = "true")
            boolean embedded,

            @Schema(description = "Number of chunks", example = "5")
            int chunks,

            @Schema(description = "Error message (on failure)")
            String error
    ) {}
}
