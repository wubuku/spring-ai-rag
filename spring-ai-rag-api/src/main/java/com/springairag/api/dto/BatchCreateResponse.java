package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Batch document creation response (unified response format)
 */
@Schema(description = "Batch document creation response")
public record BatchCreateResponse(
        @Schema(description = "Number of successfully created documents", example = "10")
        int created,

        @Schema(description = "Number of skipped documents (content unchanged/exists)", example = "2")
        int skipped,

        @Schema(description = "Number of failed documents", example = "0")
        int failed,

        @Schema(description = "Details of each document result")
        List<DocumentResult> results
) {
    @Schema(description = "Single document result")
    public record DocumentResult(
            @Schema(description = "Document ID", example = "1")
            Long documentId,

            @Schema(description = "Document title", example = "Product Manual")
            String title,

            @Schema(description = "Whether newly created (false=already exists)", example = "true")
            boolean newlyCreated,

            @Schema(description = "Error message (on failure)")
            String error
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DocumentResult that = (DocumentResult) o;
            return newlyCreated == that.newlyCreated &&
                    Objects.equals(documentId, that.documentId) &&
                    Objects.equals(title, that.title) &&
                    Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentId, title, newlyCreated, error);
        }

        @Override
        public String toString() {
            return "DocumentResult{" +
                    "documentId=" + documentId +
                    ", title='" + title + '\'' +
                    ", newlyCreated=" + newlyCreated +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchCreateResponse that = (BatchCreateResponse) o;
        return created == that.created &&
                skipped == that.skipped &&
                failed == that.failed &&
                Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(created, skipped, failed, results);
    }

    @Override
    public String toString() {
        return "BatchCreateResponse{" +
                "created=" + created +
                ", skipped=" + skipped +
                ", failed=" + failed +
                ", resultsSize=" + (results != null ? results.size() : 0) +
                '}';
    }
}
