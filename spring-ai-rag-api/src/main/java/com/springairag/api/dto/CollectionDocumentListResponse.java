package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * List documents in collection response
 */
@Schema(description = "List documents in collection response")
public record CollectionDocumentListResponse(
        @Schema(description = "Collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Stable external Collection key", example = "customer-42:manual:v3")
        String collectionKey,

        @Schema(description = "Document list")
        List<DocumentSummary> documents,

        @Schema(description = "Total count", example = "25")
        long total,

        @Schema(description = "Offset", example = "0")
        int offset,

        @Schema(description = "Limit", example = "20")
        int limit
) {
    public CollectionDocumentListResponse(Long collectionId, List<DocumentSummary> documents,
                                          long total, int offset, int limit) {
        this(collectionId, null, documents, total, offset, limit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionDocumentListResponse that = (CollectionDocumentListResponse) o;
        return total == that.total &&
                offset == that.offset &&
                limit == that.limit &&
                Objects.equals(collectionId, that.collectionId) &&
                Objects.equals(collectionKey, that.collectionKey) &&
                Objects.equals(documents, that.documents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collectionId, collectionKey, documents, total, offset, limit);
    }

    @Override
    public String toString() {
        return "CollectionDocumentListResponse{" +
                "collectionId=" + collectionId +
                ", collectionKey='" + collectionKey + '\'' +
                ", documents=" + (documents != null ? documents.size() + " document(s)" : "null") +
                ", total=" + total +
                ", offset=" + offset +
                ", limit=" + limit +
                '}';
    }
}
