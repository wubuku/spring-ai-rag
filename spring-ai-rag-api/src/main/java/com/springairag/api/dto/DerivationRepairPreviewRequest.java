package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 创建有限派生修复计划。 */
public record DerivationRepairPreviewRequest(
        @NotBlank @ValidCollectionKey String collectionKey,
        @NotEmpty List<String> buckets,
        List<String> vectorConditions,
        @Min(1) @Max(100) int maxDocuments
) {
}
