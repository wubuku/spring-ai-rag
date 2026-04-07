package com.springairag.api.dto;

/**
 * A/B test variant assignment response
 *
 * @param variant Assigned variant name
 */
public record VariantResponse(String variant) {
    public static VariantResponse of(String variant) {
        return new VariantResponse(variant);
    }
}
