package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Paginated document list response
 */
@Schema(description = "Paginated document list response")
public record DocumentListResponse(
        @Schema(description = "Document list")
        List<DocumentSummary> documents,

        @Schema(description = "Total count", example = "25")
        long total,

        @Schema(description = "Current offset", example = "0")
        int offset,

        @Schema(description = "Page size limit", example = "20")
        int limit
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentListResponse that = (DocumentListResponse) o;
        return total == that.total
                && offset == that.offset
                && limit == that.limit
                && Objects.equals(documents, that.documents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documents, total, offset, limit);
    }

    @Override
    public String toString() {
        return "DocumentListResponse{" +
                "documents=" + (documents != null ? documents.size() + " document(s)" : "null") +
                ", total=" + total +
                ", offset=" + offset +
                ", limit=" + limit +
                '}';
    }
}
