package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Clone collection response
 */
@Schema(description = "Clone collection response")
public record CollectionCloneResponse(
        @Schema(description = "Operation result message")
        String message,

        @Schema(description = "Cloned collection ID", example = "5")
        Long clonedCollectionId,

        @Schema(description = "Cloned collection key", example = "customer-42:manual:v4")
        String clonedCollectionKey,

        @Schema(description = "Cloned collection name", example = "My Knowledge Base (Copy)")
        String clonedCollectionName,

        @Schema(description = "Source collection ID", example = "1")
        Long sourceCollectionId,

        @Schema(description = "Source collection key", example = "customer-42:manual:v3")
        String sourceCollectionKey,

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
                null,
                clonedName,
                sourceId,
                null,
                sourceName,
                documentsCloned);
    }

    public static CollectionCloneResponse of(Long clonedId, String clonedKey, String clonedName,
                                             Long sourceId, String sourceKey, String sourceName,
                                             int documentsCloned) {
        return new CollectionCloneResponse("Collection cloned", clonedId, clonedKey,
                clonedName, sourceId, sourceKey, sourceName, documentsCloned);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof CollectionCloneResponse that
                && this.documentsCloned == that.documentsCloned
                && Objects.equals(this.message, that.message)
                && Objects.equals(this.clonedCollectionId, that.clonedCollectionId)
                && Objects.equals(this.clonedCollectionKey, that.clonedCollectionKey)
                && Objects.equals(this.clonedCollectionName, that.clonedCollectionName)
                && Objects.equals(this.sourceCollectionId, that.sourceCollectionId)
                && Objects.equals(this.sourceCollectionKey, that.sourceCollectionKey)
                && Objects.equals(this.sourceCollectionName, that.sourceCollectionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, clonedCollectionId, clonedCollectionKey, clonedCollectionName,
                sourceCollectionId, sourceCollectionKey, sourceCollectionName, documentsCloned);
    }

    @Override
    public String toString() {
        return "CollectionCloneResponse{message=" + message
                + ", clonedCollectionId=" + clonedCollectionId
                + ", clonedCollectionKey=" + clonedCollectionKey
                + ", clonedCollectionName=" + clonedCollectionName
                + ", sourceCollectionId=" + sourceCollectionId
                + ", sourceCollectionKey=" + sourceCollectionKey
                + ", sourceCollectionName=" + sourceCollectionName
                + ", documentsCloned=" + documentsCloned + "}";
    }
}
