package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Delete collection response
 */
@Schema(description = "Delete collection response")
public record CollectionDeleteResponse(
        @Schema(description = "Operation result message", example = "Collection deleted")
        String message,

        @Schema(description = "Collection ID", example = "1")
        Long id,

        @Schema(description = "Number of documents unlinked", example = "5")
        long documentsUnlinked
) {
    public static CollectionDeleteResponse of(Long id, long documentsUnlinked) {
        return new CollectionDeleteResponse("Collection deleted", id, documentsUnlinked);
    }

    @Override
    public String toString() {
        return "CollectionDeleteResponse{" +
                "message='" + message + '\'' +
                ", id=" + id +
                ", documentsUnlinked=" + documentsUnlinked +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionDeleteResponse that = (CollectionDeleteResponse) o;
        return documentsUnlinked == that.documentsUnlinked &&
                Objects.equals(message, that.message) &&
                Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, id, documentsUnlinked);
    }
}
