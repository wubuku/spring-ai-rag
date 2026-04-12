package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

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
}
