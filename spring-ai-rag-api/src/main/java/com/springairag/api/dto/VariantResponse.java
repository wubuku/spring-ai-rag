package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A/B test variant assignment response
 *
 * @param variant Assigned variant name
 */
@Schema(description = "A/B test variant assignment")
public record VariantResponse(
        @Schema(description = "Assigned variant name", example = "control") String variant
) {
    public static VariantResponse of(String variant) {
        return new VariantResponse(variant);
    }
}
