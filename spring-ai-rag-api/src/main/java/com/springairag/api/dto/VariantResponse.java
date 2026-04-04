package com.springairag.api.dto;

/**
 * A/B 测试变体分配响应
 *
 * @param variant 分配的变体名称
 */
public record VariantResponse(String variant) {
    public static VariantResponse of(String variant) {
        return new VariantResponse(variant);
    }
}
