package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Export collection response
 */
@Schema(description = "Export collection response")
public record CollectionExportResponse(
        @Schema(description = "Collection information")
        CollectionResponse collection,

        @Schema(description = "Number of documents", example = "42")
        int documentCount
) {
}
