package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentAddedResponse that = (DocumentAddedResponse) o;
        return Objects.equals(message, that.message) &&
                Objects.equals(collectionId, that.collectionId) &&
                Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, collectionId, documentId);
    }

    @Override
    public String toString() {
        return "DocumentAddedResponse{" +
                "message='" + message + '\'' +
                ", collectionId=" + collectionId +
                ", documentId=" + documentId +
                '}';
    }
}
