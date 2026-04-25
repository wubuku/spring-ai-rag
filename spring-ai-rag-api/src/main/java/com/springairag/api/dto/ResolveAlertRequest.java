package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

/**
 * Resolve alert request
 */
@Schema(description = "Resolve alert request")
public record ResolveAlertRequest(
        @NotBlank(message = "Resolution description is required")
        @Schema(description = "Resolution description", example = "Service restarted")
        String resolution
) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof ResolveAlertRequest that
                && Objects.equals(this.resolution, that.resolution);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resolution);
    }

    @Override
    public String toString() {
        return "ResolveAlertRequest{resolution=" + resolution + "}";
    }
}
