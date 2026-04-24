package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Objects;

/**
 * Embedding progress event (for SSE real-time push)
 *
 * @param phase Current phase (PREPARING/CHUNKING/EMBEDDING/STORING/COMPLETED/FAILED)
 * @param current Current processing count
 * @param total Total count
 * @param message Description
 * @param documentId Document ID
 */
@Schema(description = "Embedding progress event (SSE stream push)")
public record EmbedProgressEvent(
        @Schema(description = "Current phase", example = "EMBEDDING")
        String phase,

        @Schema(description = "Current processing count", example = "5")
        int current,

        @Schema(description = "Total count", example = "20")
        int total,

        @Schema(description = "Description", example = "Generating embedding for chunk 5/20")
        String message,

        @Schema(description = "Associated document ID", example = "42")
        Long documentId
) implements Serializable {

    public static EmbedProgressEvent preparing(Long docId) {
        return new EmbedProgressEvent("PREPARING", 0, 0, "Preparing document...", docId);
    }

    public static EmbedProgressEvent chunking(Long docId, int total) {
        return new EmbedProgressEvent("CHUNKING", 0, total, "Chunking, " + total + " chunks total", docId);
    }

    public static EmbedProgressEvent embedding(Long docId, int current, int total) {
        return new EmbedProgressEvent("EMBEDDING", current, total,
                "Generating embedding for chunk " + current + "/" + total, docId);
    }

    public static EmbedProgressEvent storing(Long docId, int current, int total) {
        return new EmbedProgressEvent("STORING", current, total,
                "Storing embedding " + current + "/" + total, docId);
    }

    public static EmbedProgressEvent completed(Long docId, int total) {
        return new EmbedProgressEvent("COMPLETED", total, total,
                "Embedding completed, " + total + " chunks stored", docId);
    }

    public static EmbedProgressEvent failed(Long docId, String reason) {
        return new EmbedProgressEvent("FAILED", 0, 0, "Embedding failed: " + reason, docId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmbedProgressEvent that = (EmbedProgressEvent) o;
        return current == that.current && total == that.total
                && Objects.equals(phase, that.phase)
                && Objects.equals(message, that.message)
                && Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phase, current, total, message, documentId);
    }

    @Override
    public String toString() {
        return "EmbedProgressEvent{phase='" + phase + "', current=" + current
                + ", total=" + total + ", message='" + message + "', documentId=" + documentId + "}";
    }
}
