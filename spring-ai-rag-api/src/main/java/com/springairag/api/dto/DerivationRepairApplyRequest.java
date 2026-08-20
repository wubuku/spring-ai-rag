package com.springairag.api.dto;

import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 应用已持久化派生修复计划。 */
public record DerivationRepairApplyRequest(
        @NotNull UUID repairId,
        @NotBlank @ValidCollectionKey String collectionKey,
        @NotBlank String previewToken,
        @NotBlank String previewFingerprint
) {
}
