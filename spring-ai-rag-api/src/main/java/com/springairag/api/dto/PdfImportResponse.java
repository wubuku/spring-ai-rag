package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for PDF import operations.
 */
@Schema(description = "Result of a PDF import operation")
public record PdfImportResponse(
        @Schema(description = "Virtual root path of the imported PDF", example = "papers/论文.pdf")
        String virtualRoot,

        @Schema(description = "Path to the entry Markdown file for preview", example = "papers/论文.md")
        String entryMarkdown,

        @Schema(description = "Total number of files imported (PDF + Markdown + images)")
        int filesImported
) {}
