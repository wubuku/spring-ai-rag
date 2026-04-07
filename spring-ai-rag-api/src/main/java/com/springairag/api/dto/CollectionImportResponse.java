package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Import collection response
 */
@Schema(description = "Import collection response")
public record CollectionImportResponse(
        @Schema(description = "Operation result message")
        String message,

        @Schema(description = "Collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Number of successfully imported documents", example = "10")
        int imported,

        @Schema(description = "Number of skipped documents (duplicates)", example = "2")
        int skipped
) {
    public static CollectionImportResponse of(Long collectionId, int imported, int skipped) {
        return new CollectionImportResponse("Collection import completed", collectionId, imported, skipped);
    }
}
