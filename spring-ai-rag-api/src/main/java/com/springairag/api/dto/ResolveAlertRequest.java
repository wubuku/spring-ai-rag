package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resolve alert request
 */
@Schema(description = "Resolve alert request")
public record ResolveAlertRequest(
        @Schema(description = "Resolution description", example = "Service restarted")
        String resolution
) {
}
