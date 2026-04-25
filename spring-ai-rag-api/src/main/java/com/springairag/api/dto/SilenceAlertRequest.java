package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

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
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof SilenceAlertRequest that
                && Objects.equals(this.alertKey, that.alertKey)
                && Objects.equals(this.durationMinutes, that.durationMinutes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alertKey, durationMinutes);
    }

    @Override
    public String toString() {
        return "SilenceAlertRequest{alertKey=" + alertKey + ", durationMinutes=" + durationMinutes + "}";
    }
}
