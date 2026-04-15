package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for PDF import with RAG knowledge base integration.
 *
 * <p>Returned by {@code POST /files/pdf-to-rag} when {@code embed=false}.
 * When {@code embed=true}, the endpoint returns SSE stream of embedding progress
 * followed by a final event containing this response data.
 */
@Schema(description = "Result of importing a PDF conversion (Markdown) into the RAG knowledge base")
public record PdfToRagResponse(
        @Schema(description = "RAG document ID in the knowledge base", example = "42")
        Long documentId,

        @Schema(description = "Document title (derived from original PDF filename)", example = "烟酰胺在化妆品中的应用")
        String title,

        @Schema(description = "Whether this was a newly created document. "
                + "false means the same content already existed (deduplication)", example = "true")
        boolean newlyCreated,

        @Schema(description = "Embedding status: COMPLETED, CACHED, FAILED, or null if embed=false",
                example = "COMPLETED")
        String embedStatus,

        @Schema(description = "Human-readable embedding result message", example = "Embedding generation completed")
        String embedMessage,

        @Schema(description = "Number of text chunks created and stored. "
                + "null if embed=false or embedding failed", example = "23")
        Integer chunksCreated,

        @Schema(description = "Virtual directory UUID of the imported PDF (from fs_files)",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String uuid,

        @Schema(description = "Entry Markdown path in fs_files",
                example = "550e8400-e29b-41d4-a716-446655440000/default.md")
        String entryMarkdown
) {}
