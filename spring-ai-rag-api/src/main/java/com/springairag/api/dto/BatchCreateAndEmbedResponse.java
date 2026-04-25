package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Batch create and embed documents response
 */
@Schema(description = "Batch create and embed documents response")
public record BatchCreateAndEmbedResponse(
        @Schema(description = "Number of successfully created documents", example = "10")
        int created,

        @Schema(description = "Number of successfully embedded documents", example = "10")
        int embedded,

        @Schema(description = "Number of skipped documents (content unchanged)", example = "2")
        int skipped,

        @Schema(description = "Number of failed documents", example = "0")
        int failed,

        @Schema(description = "Details of each document create+embed result")
        List<DocumentResult> results
) {
    @Schema(description = "Single document result")
    public record DocumentResult(
            @Schema(description = "Document ID", example = "1")
            Long documentId,

            @Schema(description = "Document title", example = "Product Manual")
            String title,

            @Schema(description = "Whether successfully embedded", example = "true")
            boolean embedded,

            @Schema(description = "Number of chunks", example = "5")
            int chunks,

            @Schema(description = "Error message (on failure)")
            String error
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DocumentResult that = (DocumentResult) o;
            return embedded == that.embedded &&
                    chunks == that.chunks &&
                    Objects.equals(documentId, that.documentId) &&
                    Objects.equals(title, that.title) &&
                    Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentId, title, embedded, chunks, error);
        }

        @Override
        public String toString() {
            return "DocumentResult{" +
                    "documentId=" + documentId +
                    ", title='" + title + '\'' +
                    ", embedded=" + embedded +
                    ", chunks=" + chunks +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchCreateAndEmbedResponse that = (BatchCreateAndEmbedResponse) o;
        return created == that.created &&
                embedded == that.embedded &&
                skipped == that.skipped &&
                failed == that.failed &&
                Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(created, embedded, skipped, failed, results);
    }

    @Override
    public String toString() {
        return "BatchCreateAndEmbedResponse{" +
                "created=" + created +
                ", embedded=" + embedded +
                ", skipped=" + skipped +
                ", failed=" + failed +
                ", resultsSize=" + (results != null ? results.size() : 0) +
                '}';
    }
}
