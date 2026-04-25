package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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
    public String toString() {
        return "DocumentListResponse{" +
                "documents=" + (documents != null ? documents.size() + " document(s)" : "null") +
                ", total=" + total +
                ", offset=" + offset +
                ", limit=" + limit +
                '}';
    }
}
