package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        int filesStored
) {}
