package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

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
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof EmbeddingStatusResponse that
                && this.totalDocuments == that.totalDocuments
                && this.withEmbeddings == that.withEmbeddings
                && this.withoutEmbeddings == that.withoutEmbeddings
                && this.hasMissing == that.hasMissing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalDocuments, withEmbeddings, withoutEmbeddings, hasMissing);
    }

    @Override
    public String toString() {
        return "EmbeddingStatusResponse{totalDocuments=" + totalDocuments
                + ", withEmbeddings=" + withEmbeddings
                + ", withoutEmbeddings=" + withoutEmbeddings
                + ", hasMissing=" + hasMissing + "}";
    }
}
