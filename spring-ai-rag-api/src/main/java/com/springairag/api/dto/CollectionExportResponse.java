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
        @Schema(description = "Collection name", example = "My Knowledge Base")
        String name,

        @Schema(description = "Collection description", example = "RAG documents for Q&A")
        String description,

        @Schema(description = "Embedding model used by this collection")
        String embeddingModel,

        @Schema(description = "Vector dimensions", example = "1024")
        Integer dimensions,

        @Schema(description = "Whether the collection is enabled")
        boolean enabled,

        @Schema(description = "Additional metadata")
        Map<String, Object> metadata,

        @Schema(description = "List of exported documents")
        List<ExportedDocumentSummary> documents,

        @Schema(description = "Export timestamp")
        Instant exportedAt,

        @Schema(description = "Number of documents", example = "42")
        int documentCount
) {
    /**
     * Document summary within export context (includes content).
     */
    @Schema(description = "Exported document summary with content")
    public record ExportedDocumentSummary(
            @Schema(description = "Document title", example = "Introduction to RAG")
            String title,

            @Schema(description = "Document source URL", example = "https://example.com/doc.pdf")
            String source,

            @Schema(description = "Full text content")
            String content,

            @Schema(description = "Document type", example = "PDF")
            String documentType,

            @Schema(description = "Additional metadata")
            Map<String, Object> metadata,

            @Schema(description = "Document size in bytes", example = "4096")
            Long size
    ) {
    }
}
