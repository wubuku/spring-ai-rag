package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Restore collection response
 */
@Schema(description = "Collection restore response")
public record CollectionRestoreResponse(
        @Schema(description = "Operation result message")
        String message,

        @Schema(description = "Restored collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Restored collection name", example = "My Collection")
        String name,

        @Schema(description = "Number of documents in the collection", example = "10")
        Long documentCount
) {
    public static CollectionRestoreResponse of(Long collectionId, String name, Long documentCount) {
        return new CollectionRestoreResponse("Collection restored", collectionId, name, documentCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionRestoreResponse that = (CollectionRestoreResponse) o;
        return Objects.equals(message, that.message)
                && Objects.equals(collectionId, that.collectionId)
                && Objects.equals(name, that.name)
                && Objects.equals(documentCount, that.documentCount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, collectionId, name, documentCount);
    }

    @Override
    public String toString() {
        return "CollectionRestoreResponse{" +
                "message='" + message + '\'' +
                ", collectionId=" + collectionId +
                ", name='" + name + '\'' +
                ", documentCount=" + documentCount +
                '}';
    }
}
