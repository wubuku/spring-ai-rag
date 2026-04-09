package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Batch document deletion summary")
public record BatchDeleteSummary(
        @Schema(description = "Total documents requested", example = "10")
        int total,

        @Schema(description = "Successfully deleted count", example = "8")
        int deleted,

        @Schema(description = "Not found count", example = "2")
        int notFound
) {
}
