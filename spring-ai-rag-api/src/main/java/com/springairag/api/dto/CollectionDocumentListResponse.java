package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * List documents in collection response
 */
@Schema(description = "List documents in collection response")
public record CollectionDocumentListResponse(
        @Schema(description = "Collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Document list")
        List<DocumentSummary> documents,

        @Schema(description = "Total count", example = "25")
        long total,

        @Schema(description = "Offset", example = "0")
        int offset,

        @Schema(description = "Limit", example = "20")
        int limit
) {
}
