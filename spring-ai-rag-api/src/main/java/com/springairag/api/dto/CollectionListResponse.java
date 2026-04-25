package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Collection list response
 */
@Schema(description = "Collection list response")
public record CollectionListResponse(
        @Schema(description = "Collection list")
        List<CollectionResponse> collections,

        @Schema(description = "Total count", example = "25")
        long total,

        @Schema(description = "Page number", example = "0")
        int page,

        @Schema(description = "Page size", example = "10")
        int pageSize
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionListResponse that = (CollectionListResponse) o;
        return total == that.total
                && page == that.page
                && pageSize == that.pageSize
                && Objects.equals(collections, that.collections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collections, total, page, pageSize);
    }

    @Override
    public String toString() {
        return "CollectionListResponse{" +
                "collections=" + (collections != null ? collections.size() + " collection(s)" : "null") +
                ", total=" + total +
                ", page=" + page +
                ", pageSize=" + pageSize +
                '}';
    }
}
