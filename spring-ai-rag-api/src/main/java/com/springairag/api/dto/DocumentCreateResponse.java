package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Document creation response (covers both CREATED and DUPLICATE cases)
 */
@Schema(description = "Document creation response")
public record DocumentCreateResponse(
        @Schema(description = "Document ID", example = "1")
        Long id,

        @Schema(description = "Document title", example = "My Document")
        String title,

        @Schema(description = "Operation status", example = "CREATED or DUPLICATE")
        String status,

        @Schema(description = "Response message")
        String message,

        @Schema(description = "Content hash (SHA-256)")
        String contentHash,

        @Schema(description = "Existing document ID when duplicate is detected", example = "42")
        Long existingDocumentId,

        @Schema(description = "Public business revision")
        Long documentRevision,

        DocumentLifecycleResponse lifecycle
) {
    public DocumentCreateResponse(
            Long id,
            String title,
            String status,
            String message,
            String contentHash,
            Long existingDocumentId) {
        this(id, title, status, message, contentHash,
                existingDocumentId, null, null);
    }

    public static DocumentCreateResponse created(Long id, String title, String contentHash) {
        return new DocumentCreateResponse(
                id, title, "CREATED",
                "Document created, to generate embedding call POST /api/v1/rag/documents/{id}/embed",
                contentHash, null, null, null);
    }

    public static DocumentCreateResponse created(
            Long id,
            String title,
            String contentHash,
            long documentRevision,
            DocumentLifecycleResponse lifecycle) {
        return new DocumentCreateResponse(
                id, title, "CREATED", "Document created",
                contentHash, null, documentRevision, lifecycle);
    }

    public static DocumentCreateResponse duplicate(Long existingId, String existingTitle, String existingHash) {
        return new DocumentCreateResponse(
                existingId, existingTitle, "DUPLICATE",
                "Content already exists, documentId: " + existingId,
                existingHash, existingId, null, null);
    }

    public static DocumentCreateResponse mutation(
            Long id,
            String title,
            String contentHash,
            DocumentMutationResponse mutation) {
        String action = mutation.action();
        Long existingDocumentId = "DUPLICATE".equals(action) ? id : null;
        String message = switch (action) {
            case "DUPLICATE" -> "Content already exists, documentId: " + id;
            case "REPLAYED" -> "Idempotent create replayed";
            default -> "Document created";
        };
        return new DocumentCreateResponse(
                id, title, action, message, contentHash,
                existingDocumentId, mutation.documentRevision(),
                mutation.lifecycle());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentCreateResponse that = (DocumentCreateResponse) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(title, that.title) &&
                Objects.equals(status, that.status) &&
                Objects.equals(message, that.message) &&
                Objects.equals(contentHash, that.contentHash) &&
                Objects.equals(existingDocumentId, that.existingDocumentId) &&
                Objects.equals(documentRevision, that.documentRevision) &&
                Objects.equals(lifecycle, that.lifecycle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, status, message, contentHash,
                existingDocumentId, documentRevision, lifecycle);
    }

    @Override
    public String toString() {
        return "DocumentCreateResponse{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                ", contentHash='" + contentHash + '\'' +
                ", existingDocumentId=" + existingDocumentId +
                ", documentRevision=" + documentRevision +
                ", lifecycle=" + lifecycle +
                '}';
    }
}
