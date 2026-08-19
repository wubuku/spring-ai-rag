package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Document summary in list/detail context (list view uses contentPreview instead of full content).
 */
@Schema(description = "Document summary")
public record DocumentSummary(
        @Schema(description = "Document ID", example = "1")
        Long id,

        @Schema(description = "Document title", example = "Introduction to RAG")
        String title,

        @Schema(description = "Document source URL", example = "https://example.com/doc.pdf")
        String source,

        @Schema(description = "Document type", example = "PDF")
        String documentType,

        @Schema(description = "Processing status", example = "COMPLETED")
        String processingStatus,

        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt,

        @Schema(description = "Document size in bytes", example = "4096")
        Long size,

        @Schema(description = "Content hash for cache validation")
        String contentHash,

        @Schema(description = "Whether the document is enabled", example = "true")
        boolean enabled,

        @Schema(description = "Last update timestamp")
        LocalDateTime updatedAt,

        @Schema(description = "Associated collection ID", example = "1")
        Long collectionId,

        @Schema(description = "Associated collection name", example = "My Knowledge Base")
        String collectionName,

        @Schema(description = "Number of embedding chunks", example = "5")
        long chunkCount,

        @Schema(description = "Content preview (truncated to 200 chars), present in list view")
        String contentPreview,

        @Schema(description = "Full content, present in detail view")
        String content,

        @Schema(description = "Additional metadata")
        Map<String, Object> metadata,

        @Schema(description = "Stable external Collection key", example = "customer-42:manual:v3")
        String collectionKey,

        String externalId,
        String sourceRevision,
        LocalDateTime sourceDeletedAt,
        String processingError,
        boolean embeddingFresh,
        String sourceNamespace,
        Long documentRevision,
        DocumentLifecycleResponse lifecycle
) {
    public DocumentSummary(
            Long id, String title, String source, String documentType,
            String processingStatus, LocalDateTime createdAt, Long size,
            String contentHash, boolean enabled, LocalDateTime updatedAt,
            Long collectionId, String collectionName, long chunkCount,
            String contentPreview, String content, Map<String, Object> metadata,
            String collectionKey, String externalId, String sourceRevision,
            LocalDateTime sourceDeletedAt, String processingError,
            boolean embeddingFresh) {
        this(id, title, source, documentType, processingStatus, createdAt, size,
                contentHash, enabled, updatedAt, collectionId, collectionName,
                chunkCount, contentPreview, content, metadata, collectionKey,
                externalId, sourceRevision, sourceDeletedAt, processingError,
                embeddingFresh, null, null, null);
    }

    public DocumentSummary(Long id, String title, String source, String documentType,
                           String processingStatus, LocalDateTime createdAt, Long size,
                           String contentHash, boolean enabled, LocalDateTime updatedAt,
                           Long collectionId, String collectionName, long chunkCount,
                           String contentPreview, String content, Map<String, Object> metadata) {
        this(id, title, source, documentType, processingStatus, createdAt, size, contentHash,
                enabled, updatedAt, collectionId, collectionName, chunkCount, contentPreview,
                content, metadata, null, null, null, null, null, false,
                null, null, null);
    }

    public DocumentSummary(Long id, String title, String source, String documentType,
                           String processingStatus, LocalDateTime createdAt, Long size,
                           String contentHash, boolean enabled, LocalDateTime updatedAt,
                           Long collectionId, String collectionName, long chunkCount,
                           String contentPreview, String content, Map<String, Object> metadata,
                           String collectionKey) {
        this(id, title, source, documentType, processingStatus, createdAt, size, contentHash,
                enabled, updatedAt, collectionId, collectionName, chunkCount, contentPreview,
                content, metadata, collectionKey, null, null, null, null, false,
                null, null, null);
    }

    /**
     * Compatibility constructor for callers that already supply collectionKey.
     */
    public DocumentSummary(Long id, String title, String source, String documentType,
                           String processingStatus, LocalDateTime createdAt, Long size,
                           String contentHash, Boolean enabled, LocalDateTime updatedAt,
                           Long collectionId, String collectionName, long chunkCount,
                           String contentPreview, String content, Map<String, Object> metadata,
                           String collectionKey) {
        this(id, title, source, documentType, processingStatus, createdAt, size, contentHash,
                Boolean.TRUE.equals(enabled), updatedAt, collectionId, collectionName,
                chunkCount, contentPreview, content, metadata, collectionKey,
                null, null, null, null, false, null, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentSummary that = (DocumentSummary) o;
        return enabled == that.enabled && chunkCount == that.chunkCount
                && Objects.equals(id, that.id)
                && Objects.equals(title, that.title)
                && Objects.equals(source, that.source)
                && Objects.equals(documentType, that.documentType)
                && Objects.equals(processingStatus, that.processingStatus)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(size, that.size)
                && Objects.equals(contentHash, that.contentHash)
                && Objects.equals(updatedAt, that.updatedAt)
                && Objects.equals(collectionId, that.collectionId)
                && Objects.equals(collectionKey, that.collectionKey)
                && Objects.equals(collectionName, that.collectionName)
                && Objects.equals(externalId, that.externalId)
                && Objects.equals(sourceRevision, that.sourceRevision)
                && Objects.equals(sourceDeletedAt, that.sourceDeletedAt)
                && Objects.equals(processingError, that.processingError)
                && embeddingFresh == that.embeddingFresh
                && Objects.equals(sourceNamespace, that.sourceNamespace)
                && Objects.equals(documentRevision, that.documentRevision)
                && Objects.equals(lifecycle, that.lifecycle)
                && Objects.equals(contentPreview, that.contentPreview)
                && Objects.equals(content, that.content)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, source, documentType, processingStatus,
                createdAt, size, contentHash, enabled, updatedAt,
                collectionId, collectionName, chunkCount, contentPreview, content, metadata,
                collectionKey, externalId, sourceRevision, sourceDeletedAt,
                processingError, embeddingFresh, sourceNamespace,
                documentRevision, lifecycle);
    }

    @Override
    public String toString() {
        return "DocumentSummary{id=" + id + ", title='" + title + "', source='" + source
                + "', documentType='" + documentType + "', processingStatus='" + processingStatus
                + "', createdAt=" + createdAt + ", size=" + size + ", contentHash='" + contentHash
                + "', enabled=" + enabled + ", updatedAt=" + updatedAt
                + ", collectionId=" + collectionId + ", collectionName='" + collectionName
                + "', collectionKey='" + collectionKey
                + "', externalId='" + externalId
                + "', sourceRevision='" + sourceRevision
                + "', sourceNamespace='" + sourceNamespace
                + "', documentRevision=" + documentRevision
                + "', chunkCount=" + chunkCount + ", contentPreview='" + contentPreview
                + "', content='" + (content != null && content.length() > 50
                        ? content.substring(0, 50) + "..." : content) + "'"
                + ", metadata=" + metadata + "}";
    }
}
