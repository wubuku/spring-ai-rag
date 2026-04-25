package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

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

    @Override
    public String toString() {
        return "CollectionCreatedResponse{" +
                "message='" + message + '\'' +
                ", collectionId=" + collectionId +
                ", name='" + name + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionCreatedResponse that = (CollectionCreatedResponse) o;
        return Objects.equals(message, that.message) &&
                Objects.equals(collectionId, that.collectionId) &&
                Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, collectionId, name);
    }
}
