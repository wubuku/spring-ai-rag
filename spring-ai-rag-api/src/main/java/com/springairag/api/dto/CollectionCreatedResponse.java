package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Create collection response
 */
@Schema(description = "Collection creation response")
public record CollectionCreatedResponse(
        @Schema(description = "Operation result message")
        String message,

        @Schema(description = "Collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Collection name", example = "My Collection")
        String name
) {
    public static CollectionCreatedResponse of(Long collectionId, String name) {
        return new CollectionCreatedResponse("Collection created", collectionId, name);
    }
}
