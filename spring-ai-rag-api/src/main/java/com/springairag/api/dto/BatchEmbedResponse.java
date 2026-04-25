package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Batch embedding response (POST /batch/embed).
 */
@Schema(description = "Batch embedding response")
public record BatchEmbedResponse(
        @Schema(description = "Per-document embedding results")
        List<BatchEmbedResultItem> results,

        @Schema(description = "Batch summary")
        BatchEmbedSummary summary
) {
    @Schema(description = "Single document embedding result")
    public record BatchEmbedResultItem(
            @Schema(description = "Document ID", example = "1")
            Long documentId,

            @Schema(description = "Status: COMPLETED, CACHED, FAILED, NOT_FOUND, or SKIPPED",
                    example = "COMPLETED")
            String status,

            @Schema(description = "Number of chunks created (COMPLETED only)", example = "5")
            Integer chunksCreated,

            @Schema(description = "Number of embeddings stored", example = "5")
            Integer embeddingsStored,

            @Schema(description = "Error message (FAILED only)")
            String error,

            @Schema(description = "Reason for SKIPPED status")
            String reason
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BatchEmbedResultItem that = (BatchEmbedResultItem) o;
            return Objects.equals(documentId, that.documentId) &&
                    Objects.equals(status, that.status) &&
                    Objects.equals(chunksCreated, that.chunksCreated) &&
                    Objects.equals(embeddingsStored, that.embeddingsStored) &&
                    Objects.equals(error, that.error) &&
                    Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentId, status, chunksCreated, embeddingsStored, error, reason);
        }

        @Override
        public String toString() {
            return "BatchEmbedResultItem{" +
                    "documentId=" + documentId +
                    ", status='" + status + '\'' +
                    ", chunksCreated=" + chunksCreated +
                    ", embeddingsStored=" + embeddingsStored +
                    ", error='" + error + '\'' +
                    ", reason='" + reason + '\'' +
                    '}';
        }
    }

    @Schema(description = "Batch summary counts")
    public record BatchEmbedSummary(
            @Schema(description = "Total documents in batch", example = "10")
            int total,

            @Schema(description = "Successfully embedded", example = "5")
            int success,

            @Schema(description = "Cache hits (skipped re-embedding)", example = "3")
            int cached,

            @Schema(description = "Failed to embed", example = "1")
            int failed,

            @Schema(description = "Skipped (empty content or no chunking needed)", example = "1")
            int skipped
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BatchEmbedSummary that = (BatchEmbedSummary) o;
            return total == that.total &&
                    success == that.success &&
                    cached == that.cached &&
                    failed == that.failed &&
                    skipped == that.skipped;
        }

        @Override
        public int hashCode() {
            return Objects.hash(total, success, cached, failed, skipped);
        }

        @Override
        public String toString() {
            return "BatchEmbedSummary{" +
                    "total=" + total +
                    ", success=" + success +
                    ", cached=" + cached +
                    ", failed=" + failed +
                    ", skipped=" + skipped +
                    '}';
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchEmbedResponse that = (BatchEmbedResponse) o;
        return Objects.equals(results, that.results) &&
                Objects.equals(summary, that.summary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, summary);
    }

    @Override
    public String toString() {
        return "BatchEmbedResponse{" +
                "resultsSize=" + (results != null ? results.size() : 0) +
                ", summary=" + summary +
                '}';
    }
}
