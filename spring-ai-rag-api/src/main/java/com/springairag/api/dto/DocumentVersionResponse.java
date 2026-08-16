package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Document version response (single version detail or list item).
 */
@Schema(description = "Document version response")
public record DocumentVersionResponse(
        @Schema(description = "Version record ID", example = "1")
        Long id,

        @Schema(description = "Parent document ID", example = "42")
        Long documentId,

        @Schema(description = "Version number (incremental)", example = "3")
        int versionNumber,

        @Schema(description = "Content hash", example = "sha256:abc123...")
        String contentHash,

        @Schema(description = "Caller-supplied source revision snapshot")
        String sourceRevisionSnapshot,

        @Schema(description = "Size in bytes", example = "4096")
        Long size,

        @Schema(description = "Change type: CREATED, UPDATED, DELETED", example = "UPDATED")
        String changeType,

        @Schema(description = "Human-readable change description", example = "Content updated via batch import")
        String changeDescription,

        @Schema(description = "Version creation timestamp")
        LocalDateTime createdAt,

        @Schema(description = "Content snapshot (only in single version detail, omitted in list)")
        String contentSnapshot,

        @Schema(description = "JSONB payload snapshot (only in single version detail, omitted in list)")
        JsonNode jsonbPayloadSnapshot
) {
    public DocumentVersionResponse(
            Long id,
            Long documentId,
            int versionNumber,
            String contentHash,
            Long size,
            String changeType,
            String changeDescription,
            LocalDateTime createdAt,
            String contentSnapshot) {
        this(id, documentId, versionNumber, contentHash, null, size, changeType,
                changeDescription, createdAt, contentSnapshot, null);
    }

    public DocumentVersionResponse(
            Long id,
            Long documentId,
            int versionNumber,
            String contentHash,
            Long size,
            String changeType,
            String changeDescription,
            LocalDateTime createdAt,
            String contentSnapshot,
            JsonNode jsonbPayloadSnapshot) {
        this(id, documentId, versionNumber, contentHash, null, size, changeType,
                changeDescription, createdAt, contentSnapshot, jsonbPayloadSnapshot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentVersionResponse that = (DocumentVersionResponse) o;
        return versionNumber == that.versionNumber
                && Objects.equals(id, that.id)
                && Objects.equals(documentId, that.documentId)
                && Objects.equals(contentHash, that.contentHash)
                && Objects.equals(sourceRevisionSnapshot, that.sourceRevisionSnapshot)
                && Objects.equals(size, that.size)
                && Objects.equals(changeType, that.changeType)
                && Objects.equals(changeDescription, that.changeDescription)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(contentSnapshot, that.contentSnapshot)
                && Objects.equals(jsonbPayloadSnapshot, that.jsonbPayloadSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, documentId, versionNumber, contentHash, sourceRevisionSnapshot, size, changeType,
                changeDescription, createdAt, contentSnapshot, jsonbPayloadSnapshot);
    }

    @Override
    public String toString() {
        return "DocumentVersionResponse{" +
                "id=" + id +
                ", documentId=" + documentId +
                ", versionNumber=" + versionNumber +
                ", contentHash='" + contentHash + "'" +
                ", sourceRevisionSnapshot='" + sourceRevisionSnapshot + "'" +
                ", size=" + size +
                ", changeType='" + changeType + "'" +
                ", changeDescription='" + changeDescription + "'" +
                ", createdAt=" + createdAt +
                ", contentSnapshotLength=" + (contentSnapshot != null ? contentSnapshot.length() : 0) +
                ", hasJsonbPayloadSnapshot=" + (jsonbPayloadSnapshot != null) +
                '}';
    }
}
