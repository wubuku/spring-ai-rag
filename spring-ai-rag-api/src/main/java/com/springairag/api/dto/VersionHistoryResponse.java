package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Document version history paginated response.
 */
@Schema(description = "Document version history response")
public record VersionHistoryResponse(
        @Schema(description = "Parent document ID", example = "42")
        Long documentId,

        @Schema(description = "Total number of versions", example = "7")
        long totalVersions,

        @Schema(description = "Current page number", example = "0")
        int page,

        @Schema(description = "Page size", example = "20")
        int size,

        @Schema(description = "Version records for this page")
        List<DocumentVersionResponse> versions
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VersionHistoryResponse that = (VersionHistoryResponse) o;
        return totalVersions == that.totalVersions &&
                page == that.page &&
                size == that.size &&
                Objects.equals(documentId, that.documentId) &&
                Objects.equals(versions, that.versions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, totalVersions, page, size, versions);
    }

    @Override
    public String toString() {
        return "VersionHistoryResponse{" +
                "documentId=" + documentId +
                ", totalVersions=" + totalVersions +
                ", page=" + page +
                ", size=" + size +
                ", versions=" + (versions != null ? versions.size() + " version(s)" : "null") +
                '}';
    }
}
