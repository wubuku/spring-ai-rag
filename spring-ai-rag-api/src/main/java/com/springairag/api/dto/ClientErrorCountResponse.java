package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Client error count response.
 *
 * @param count Total number of recorded client-side errors
 */
@Schema(description = "Total count of recorded client-side errors")
public record ClientErrorCountResponse(
        @Schema(description = "Total number of recorded client errors", example = "42") long count
) {
}
