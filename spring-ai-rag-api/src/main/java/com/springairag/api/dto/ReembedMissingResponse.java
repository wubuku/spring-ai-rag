package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Response for batch re-embedding documents lacking embedding vectors")
public record ReembedMissingResponse(
        @Schema(description = "Total documents processed") int total,
        @Schema(description = "Number successfully re-embedded") int success,
        @Schema(description = "Number that failed") int failed,
        @Schema(description = "Individual re-embed results") List<ReembedResultResponse> results
) {
}
