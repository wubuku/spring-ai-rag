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
        Long existingDocumentId
) {
    public static DocumentCreateResponse created(Long id, String title, String contentHash) {
        return new DocumentCreateResponse(
                id, title, "CREATED",
                "Document created, to generate embedding call POST /api/v1/rag/documents/{id}/embed",
                contentHash, null);
    }

    public static DocumentCreateResponse duplicate(Long existingId, String existingTitle, String existingHash) {
        return new DocumentCreateResponse(
                existingId, existingTitle, "DUPLICATE",
                "Content already exists, documentId: " + existingId,
                existingHash, existingId);
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
                Objects.equals(existingDocumentId, that.existingDocumentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, status, message, contentHash, existingDocumentId);
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
                '}';
    }
}
