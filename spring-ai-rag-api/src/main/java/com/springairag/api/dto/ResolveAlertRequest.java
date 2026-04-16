package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Resolve alert request
 */
@Schema(description = "Resolve alert request")
public record ResolveAlertRequest(
        @NotBlank(message = "Resolution description is required")
        @Schema(description = "Resolution description", example = "Service restarted")
        String resolution
) {
}
