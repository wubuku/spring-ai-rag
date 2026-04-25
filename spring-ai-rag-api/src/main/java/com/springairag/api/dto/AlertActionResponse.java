package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * Alert action response
 *
 * @param success Whether the operation succeeded
 * @param message Operation result message
 */
@Schema(description = "Alert action result — indicates whether the operation succeeded")
public record AlertActionResponse(
        @Schema(description = "Whether the operation succeeded", example = "true") boolean success,
        @Schema(description = "Human-readable result message", example = "Alert silenced for 2 hours") String message
) {
    public static AlertActionResponse ok(String message) {
        return new AlertActionResponse(true, message);
    }

    public static AlertActionResponse fail(String message) {
        return new AlertActionResponse(false, message);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof AlertActionResponse that
                && this.success == that.success
                && Objects.equals(this.message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, message);
    }

    @Override
    public String toString() {
        return "AlertActionResponse{success=" + success + ", message=" + message + "}";
    }
}
