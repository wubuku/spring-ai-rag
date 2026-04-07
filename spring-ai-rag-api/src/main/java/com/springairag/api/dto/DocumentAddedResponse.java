package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Add document to collection response
 */
@Schema(description = "Add document to collection response")
public record DocumentAddedResponse(
        @Schema(description = "Operation result message")
        String message,

        @Schema(description = "Collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Document ID", example = "1")
        Long documentId
) {
    public static DocumentAddedResponse of(Long collectionId, Long documentId) {
        return new DocumentAddedResponse("Document added to collection", collectionId, documentId);
    }
}
