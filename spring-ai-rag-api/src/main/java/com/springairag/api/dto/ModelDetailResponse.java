package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Objects;

/**
 * Model detail response
 *
 * @param available Whether the model is available
 * @param details Model details (provider/name/displayName, etc.)
 */
@Schema(description = "Single model details and availability")
public record ModelDetailResponse(
        @Schema(description = "Whether this model is currently available", example = "true") boolean available,
        @Schema(description = "Model details (provider, name, displayName, etc.)") Map<String, Object> details
) {
    public static ModelDetailResponse of(boolean available, Map<String, Object> details) {
        return new ModelDetailResponse(available, details);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelDetailResponse that = (ModelDetailResponse) o;
        return available == that.available &&
                Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(available, details);
    }

    @Override
    public String toString() {
        return "ModelDetailResponse{" +
                "available=" + available +
                ", details=" + details +
                '}';
    }
}
