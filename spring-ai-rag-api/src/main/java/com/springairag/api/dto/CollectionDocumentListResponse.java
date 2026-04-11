package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Document list response for a specific collection.
 */
@Schema(description = "Document list response for a collection")
public record CollectionDocumentListResponse(
        @Schema(description = "Collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Document list")
        List<DocumentSummary> documents,

        @Schema(description = "Total count", example = "25")
        long total,

        @Schema(description = "Page offset", example = "0")
        int offset,

        @Schema(description = "Page size", example = "20")
        int limit
) {
    /**
     * Document summary in collection document list.
     */
    @Schema(description = "Document summary")
    public record DocumentSummary(
            @Schema(description = "Document ID", example = "1")
            Long id,

            @Schema(description = "Document title", example = "My Document")
            String title,

            @Schema(description = "Document source")
            String source,

            @Schema(description = "Document type")
            String documentType,

            @Schema(description = "Processing status")
            String processingStatus,

            @Schema(description = "Creation timestamp")
            Object createdAt,

            @Schema(description = "Document size in bytes")
            Long size
    ) {
    }
}
