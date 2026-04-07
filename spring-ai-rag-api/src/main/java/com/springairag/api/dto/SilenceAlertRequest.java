package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Silence alert request
 */
@Schema(description = "Silence alert request")
public record SilenceAlertRequest(
        @Schema(description = "Alert key", example = "high-latency")
        String alertKey,

        @Schema(description = "Silence duration in minutes", example = "60")
        Integer durationMinutes
) {
}
