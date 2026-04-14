package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Document statistics response (counts by processing status).
 */
@Schema(description = "Document statistics response")
public record DocumentStatsResponse(
        @Schema(description = "Total document count", example = "42")
        long total,

        @Schema(description = "Counts grouped by processing status")
        Map<String, Long> byStatus
) {
}
