package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof VariantResponse that
                && Objects.equals(this.variant, that.variant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variant);
    }

    @Override
    public String toString() {
        return "VariantResponse{variant=" + variant + "}";
    }
}
