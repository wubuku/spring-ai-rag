package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Batch document deletion response")
public record BatchDeleteResponse(
        @Schema(description = "Individual deletion results")
        List<BatchDeleteItem> results,

        @Schema(description = "Batch operation summary")
        BatchDeleteSummary summary
) {
}
