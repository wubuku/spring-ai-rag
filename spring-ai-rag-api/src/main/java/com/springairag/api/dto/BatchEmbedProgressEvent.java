package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Batch embedding progress event (for SSE real-time push)
 *
 * @param currentDocIndex Current document index (0-based)
 * @param totalDocs       Total number of documents
 * @param currentDocId    Currently processing document ID
 * @param phase           Current phase (PREPARING/CHUNKING/EMBEDDING/STORING/COMPLETED/FAILED)
 * @param current         Current document chunk progress (chunk number)
 * @param total           Current document total chunks
 * @param message         Description
 * @param successCount    Number of successful operations
 * @param failedCount     Number of failed operations
 * @param cachedCount     Number of cache hits
 */
@Schema(description = "Batch embedding progress event (SSE stream push)")
public record BatchEmbedProgressEvent(
        @Schema(description = "Current document index (0-based)", example = "3")
        int currentDocIndex,

        @Schema(description = "Total number of documents", example = "20")
        int totalDocs,

        @Schema(description = "Currently processing document ID", example = "42")
        Long currentDocId,

        @Schema(description = "Current phase", example = "EMBEDDING")
        String phase,

        @Schema(description = "Current document chunk progress", example = "5")
        int current,

        @Schema(description = "Current document total chunks", example = "10")
        int total,

        @Schema(description = "Description", example = "Document 4/20: Generating embedding for chunk 5/10")
        String message,

        @Schema(description = "Number of successful operations", example = "2")
        int successCount,

        @Schema(description = "Number of failed operations", example = "0")
        int failedCount,

        @Schema(description = "Number of cache hits", example = "1")
        int cachedCount
) {
    public int overallPercent() {
        if (totalDocs == 0) return 0;
        return (currentDocIndex * 100) / totalDocs;
    }
}
