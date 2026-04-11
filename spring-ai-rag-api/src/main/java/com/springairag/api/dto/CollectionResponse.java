package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;

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
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt,

        @Schema(description = "Whether the collection is soft-deleted")
        boolean deleted,

        @Schema(description = "Soft-delete timestamp")
        LocalDateTime deletedAt,

        @Schema(description = "Number of documents")
        long documentCount
) {
}
