package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Manual alert trigger request
 */
@Schema(description = "Manual alert trigger request")
public record FireAlertRequest(
        @Schema(description = "Alert type", example = "manual")
        String alertType,

        @Schema(description = "Alert name", example = "Manual test alert")
        String alertName,

        @Schema(description = "Alert message", example = "This is a manually triggered test alert")
        String message,

        @Schema(description = "Severity level", example = "WARNING")
        String severity,

        @Schema(description = "Associated metrics")
        Map<String, Object> metrics
) {
}
