package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 批量创建文档响应（统一响应格式）
 */
@Schema(description = "Batch document creation response")
public record BatchCreateResponse(
        @Schema(description = "Number of successfully created documents", example = "10")
        int created,

        @Schema(description = "Number of skipped documents (content unchanged/exists)", example = "2")
        int skipped,

        @Schema(description = "Number of failed documents", example = "0")
        int failed,

        @Schema(description = "Details of each document result")
        List<DocumentResult> results
) {
    @Schema(description = "Single document result")
    public record DocumentResult(
            @Schema(description = "Document ID", example = "1")
            Long documentId,

            @Schema(description = "Document title", example = "Product Manual")
            String title,

            @Schema(description = "Whether newly created (false=already exists)", example = "true")
            boolean newlyCreated,

            @Schema(description = "Error message (on failure)")
            String error
    ) {}
}
