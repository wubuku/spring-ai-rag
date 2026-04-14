package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Embedding vector status response (how many documents lack vectors).
 */
@Schema(description = "Embedding status response")
public record EmbeddingStatusResponse(
        @Schema(description = "Total number of documents", example = "42")
        long totalDocuments,

        @Schema(description = "Documents with embedding vectors", example = "35")
        long withEmbeddings,

        @Schema(description = "Documents without embedding vectors", example = "7")
        long withoutEmbeddings,

        @Schema(description = "Whether any documents are missing embeddings", example = "true")
        boolean hasMissing
) {
}
