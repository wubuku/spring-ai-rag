package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Collection document list response
 */
@Schema(description = "Collection document list response")
public record DocumentListResponse(
        @Schema(description = "Document list")
        List<?> documents,

        @Schema(description = "Total count", example = "25")
        long total
) {
}
