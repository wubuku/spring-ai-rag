package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Export collection response
 */
@Schema(description = "Export collection response")
public record CollectionExportResponse(
        @Schema(description = "Collection information")
        CollectionResponse collection,

        @Schema(description = "Number of documents", example = "42")
        int documentCount,

        @Schema(description = "Export timestamp")
        Instant exportedAt,

        @Schema(description = "Document details")
        List<DocumentDetail> documents
) {
    /**
     * Document detail in export.
     */
    @Schema(description = "Document detail")
    public record DocumentDetail(
            @Schema(description = "Document title")
            String title,

            @Schema(description = "Document source")
            String source,

            @Schema(description = "Document content")
            String content,

            @Schema(description = "Document type")
            String documentType,

            @Schema(description = "Document metadata")
            Map<String, Object> metadata,

            @Schema(description = "Document size in bytes")
            Long size
    ) {
    }
}
