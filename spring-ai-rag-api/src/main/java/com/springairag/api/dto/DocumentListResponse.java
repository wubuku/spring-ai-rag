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
}
