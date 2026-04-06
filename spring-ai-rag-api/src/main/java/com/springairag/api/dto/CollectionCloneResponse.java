package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Clone collection response
 */
@Schema(description = "Clone collection response")
public record CollectionCloneResponse(
        @Schema(description = "Operation result message")
        String message,

        @Schema(description = "Cloned collection ID", example = "5")
        Long clonedCollectionId,

        @Schema(description = "Cloned collection name", example = "My Knowledge Base (Copy)")
        String clonedCollectionName,

        @Schema(description = "Source collection ID", example = "1")
        Long sourceCollectionId,

        @Schema(description = "Source collection name", example = "My Knowledge Base")
        String sourceCollectionName,

        @Schema(description = "Number of documents cloned", example = "10")
        int documentsCloned
) {
    public static CollectionCloneResponse of(Long clonedId, String clonedName,
                                              Long sourceId, String sourceName,
                                              int documentsCloned) {
        return new CollectionCloneResponse(
                "Collection cloned",
                clonedId,
                clonedName,
                sourceId,
                sourceName,
                documentsCloned);
    }
}
