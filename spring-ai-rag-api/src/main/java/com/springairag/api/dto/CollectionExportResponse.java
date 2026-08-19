package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Export collection response
 */
@Schema(description = "Export collection response")
public record CollectionExportResponse(
        @Schema(description = "Collection name", example = "My Knowledge Base")
        String name,

        @Schema(description = "Stable collection key")
        String collectionKey,

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
            Long size,

            @Schema(description = "Structured-record external identity")
            String externalId,

            @Schema(description = "External connector identity namespace")
            String sourceNamespace,

            @Schema(description = "Opaque source revision for external synchronization")
            String sourceRevision,

            @Schema(description = "Source-managed tombstone timestamp")
            LocalDateTime sourceDeletedAt,

            @Schema(description = "Structured JSONB payload")
            JsonNode jsonbPayload,

            @Schema(description = "Original uploaded filename")
            String originalFilename,

            @Schema(description = "Whether the document is enabled")
            Boolean enabled
    ) {
        public ExportedDocumentSummary(
                String title,
                String source,
                String content,
                String documentType,
                Map<String, Object> metadata,
                Long size) {
            this(title, source, content, documentType, metadata, size,
                    null, "default", null, null, null, null, true);
        }

        /**
         * Backward-compatible constructor for callers that already supplied
         * structured-record fields but predate external synchronization fields.
         */
        public ExportedDocumentSummary(
                String title,
                String source,
                String content,
                String documentType,
                Map<String, Object> metadata,
                Long size,
                String externalId,
                JsonNode jsonbPayload,
                String originalFilename,
                Boolean enabled) {
            this(title, source, content, documentType, metadata, size,
                    externalId, "default", null, null,
                    jsonbPayload, originalFilename, enabled);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ExportedDocumentSummary that = (ExportedDocumentSummary) o;
            return Objects.equals(title, that.title)
                    && Objects.equals(source, that.source)
                    && Objects.equals(content, that.content)
                    && Objects.equals(documentType, that.documentType)
                    && Objects.equals(metadata, that.metadata)
                    && Objects.equals(size, that.size)
                    && Objects.equals(externalId, that.externalId)
                    && Objects.equals(sourceNamespace, that.sourceNamespace)
                    && Objects.equals(sourceRevision, that.sourceRevision)
                    && Objects.equals(sourceDeletedAt, that.sourceDeletedAt)
                    && Objects.equals(jsonbPayload, that.jsonbPayload)
                    && Objects.equals(originalFilename, that.originalFilename)
                    && Objects.equals(enabled, that.enabled);
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, source, content, documentType, metadata, size,
                    externalId, sourceNamespace, sourceRevision, sourceDeletedAt,
                    jsonbPayload, originalFilename, enabled);
        }

        @Override
        public String toString() {
            return "ExportedDocumentSummary{" +
                    "title='" + title + "'" +
                    ", source='" + source + "'" +
                    ", contentLength=" + (content != null ? content.length() : 0) +
                    ", documentType='" + documentType + "'" +
                    ", metadata=" + metadata +
                    ", size=" + size +
                    ", externalId='" + externalId + "'" +
                    ", sourceNamespace='" + sourceNamespace + "'" +
                    ", sourceRevision='" + sourceRevision + "'" +
                    ", sourceDeletedAt=" + sourceDeletedAt +
                    ", hasJsonbPayload=" + (jsonbPayload != null) +
                    ", originalFilename='" + originalFilename + "'" +
                    ", enabled=" + enabled +
                    '}';
        }
    }

    public CollectionExportResponse(
            String name,
            String description,
            String embeddingModel,
            Integer dimensions,
            boolean enabled,
            Map<String, Object> metadata,
            List<ExportedDocumentSummary> documents,
            Instant exportedAt,
            int documentCount) {
        this(name, null, description, embeddingModel, dimensions, enabled,
                metadata, documents, exportedAt, documentCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionExportResponse that = (CollectionExportResponse) o;
        return enabled == that.enabled
                && Objects.equals(name, that.name)
                && Objects.equals(collectionKey, that.collectionKey)
                && Objects.equals(description, that.description)
                && Objects.equals(embeddingModel, that.embeddingModel)
                && Objects.equals(dimensions, that.dimensions)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(documents, that.documents)
                && Objects.equals(exportedAt, that.exportedAt)
                && documentCount == that.documentCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, collectionKey, description, embeddingModel, dimensions,
                enabled, metadata, documents, exportedAt, documentCount);
    }

    @Override
    public String toString() {
        return "CollectionExportResponse{" +
                "name='" + name + "'" +
                ", collectionKey='" + collectionKey + "'" +
                ", description='" + description + "'" +
                ", embeddingModel='" + embeddingModel + "'" +
                ", dimensions=" + dimensions +
                ", enabled=" + enabled +
                ", metadata=" + metadata +
                ", documents count=" + (documents != null ? documents.size() : 0) +
                ", documentCount=" + documentCount +
                ", exportedAt=" + exportedAt +
                '}';
    }
}
