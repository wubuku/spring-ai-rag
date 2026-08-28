package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Response DTO for PDF import operations.
 *
 * <p>After importing, preview the entry Markdown at:
 * {@code GET /files/preview?path={uuid}/original.pdf}
 * which automatically derives the entry Markdown path ({@code {uuid}/default.md}).
 */
@Schema(description = "Result of a PDF import operation")
public record PdfImportResponse(
        @Schema(description = "Virtual directory UUID — the unique ID for this import", example = "550e8400-e29b-41d4-a716-446655440000")
        String uuid,

        @Schema(description = "Path to the entry Markdown file", example = "550e8400-e29b-41d4-a716-446655440000/default.md")
        String entryMarkdown,

        @Schema(description = "Total number of files stored (PDF + Markdown)")
        int filesStored,

        @Schema(description = "Normalized original upload filename")
        String originalFilename,

        @Schema(description = "Human-readable display name")
        String displayName
) {
    public PdfImportResponse(String uuid, String entryMarkdown, int filesStored) {
        this(uuid, entryMarkdown, filesStored, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PdfImportResponse that = (PdfImportResponse) o;
        return filesStored == that.filesStored &&
                Objects.equals(uuid, that.uuid) &&
                Objects.equals(entryMarkdown, that.entryMarkdown) &&
                Objects.equals(originalFilename, that.originalFilename) &&
                Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, entryMarkdown, filesStored, originalFilename, displayName);
    }

    @Override
    public String toString() {
        return "PdfImportResponse{" +
                "uuid='" + uuid + '\'' +
                ", entryMarkdown='" + entryMarkdown + '\'' +
                ", filesStored=" + filesStored +
                ", originalFilename='" + originalFilename + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
