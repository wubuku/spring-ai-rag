package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Silence alert request
 */
@Schema(description = "Silence alert request")
public record SilenceAlertRequest(
        @NotBlank(message = "Alert key is required")
        @Size(max = 128, message = "Alert key must not exceed 128 characters")
        @Schema(description = "Alert key", example = "high-latency")
        String alertKey,

        @Min(value = 1, message = "Duration must be at least 1 minute")
        @Schema(description = "Silence duration in minutes", example = "60")
        Integer durationMinutes
) {
}
