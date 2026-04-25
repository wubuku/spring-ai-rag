package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Result of re-embedding a single document
 */
@Schema(description = "Result of re-embedding a single document")
public record ReembedResultResponse(
        @Schema(description = "Document ID") Long documentId,
        @Schema(description = "Document title") String title,
        @Schema(description = "Re-embedding status (COMPLETED or error)") String status,
        @Schema(description = "Number of chunks created") Integer chunks,
        @Schema(description = "Status message or error details") String message
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReembedResultResponse that = (ReembedResultResponse) o;
        return Objects.equals(documentId, that.documentId) &&
                Objects.equals(title, that.title) &&
                Objects.equals(status, that.status) &&
                Objects.equals(chunks, that.chunks) &&
                Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, title, status, chunks, message);
    }

    @Override
    public String toString() {
        return "ReembedResultResponse{" +
                "documentId=" + documentId +
                ", title='" + title + '\'' +
                ", status='" + status + '\'' +
                ", chunks=" + chunks +
                ", message='" + message + '\'' +
                '}';
    }
}
