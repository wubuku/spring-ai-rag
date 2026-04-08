package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Manual alert trigger request
 */
@Schema(description = "Manual alert trigger request")
public record FireAlertRequest(
        @NotBlank(message = "Alert type is required")
        @Size(max = 64, message = "Alert type must not exceed 64 characters")
        @Schema(description = "Alert type", example = "manual")
        String alertType,

        @NotBlank(message = "Alert name is required")
        @Size(max = 128, message = "Alert name must not exceed 128 characters")
        @Schema(description = "Alert name", example = "Manual test alert")
        String alertName,

        @Size(max = 1024, message = "Alert message must not exceed 1024 characters")
        @Schema(description = "Alert message", example = "This is a manually triggered test alert")
        String message,

        @Size(max = 32, message = "Severity level must not exceed 32 characters")
        @Schema(description = "Severity level", example = "WARNING")
        String severity,

        @Schema(description = "Associated metrics")
        Map<String, Object> metrics
) {
}
