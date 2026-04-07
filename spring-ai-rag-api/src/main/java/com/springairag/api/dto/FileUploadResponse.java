package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * File upload and embed response
 */
@Schema(description = "File upload and embed response")
public record FileUploadResponse(
        @Schema(description = "Number of successfully processed documents", example = "10")
        int processed,

        @Schema(description = "Number of successful documents", example = "10")
        int success,

        @Schema(description = "Number of failed documents", example = "0")
        int failed,

        @Schema(description = "Processing results for each file")
        List<FileResult> results
) {
    @Schema(description = "Single file processing result")
    public record FileResult(
            @Schema(description = "Original filename", example = "product-manual.txt")
            String filename,

            @Schema(description = "Document ID (on success)", example = "1")
            Long documentId,

            @Schema(description = "Document title", example = "Product Manual")
            String title,

            @Schema(description = "Whether successfully embedded", example = "true")
            boolean embedded,

            @Schema(description = "Number of chunks (on success)", example = "5")
            int chunks,

            @Schema(description = "Error message (on failure)")
            String error
    ) {}
}
