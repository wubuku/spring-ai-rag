package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Collection detail response (used by getById, export, etc.)
 */
@Schema(description = "Collection detail response")
public record CollectionResponse(
        @Schema(description = "Collection ID", example = "1")
        Long id,
        @Schema(description = "Collection name", example = "My Knowledge Base")
        String name,
        @Schema(description = "Description")
        String description,
        @Schema(description = "Embedding model", example = "BAAI/bge-m3")
        String embeddingModel,
        @Schema(description = "Vector dimensions", example = "1024")
        int dimensions,
        @Schema(description = "Whether the collection is enabled")
        boolean enabled,
        @Schema(description = "Metadata")
        Map<String, Object> metadata,
        @Schema(description = "Creation timestamp")
        ZonedDateTime createdAt,
        @Schema(description = "Last update timestamp")
        ZonedDateTime updatedAt,
        @Schema(description = "Number of documents")
        long documentCount
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionResponse that = (CollectionResponse) o;
        return dimensions == that.dimensions
                && enabled == that.enabled
                && documentCount == that.documentCount
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(embeddingModel, that.embeddingModel)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, embeddingModel, dimensions, enabled, metadata, createdAt, updatedAt, documentCount);
    }

    @Override
    public String toString() {
        return "CollectionResponse{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", embeddingModel='" + embeddingModel + '\'' +
                ", dimensions=" + dimensions +
                ", enabled=" + enabled +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", documentCount=" + documentCount +
                '}';
    }
}
