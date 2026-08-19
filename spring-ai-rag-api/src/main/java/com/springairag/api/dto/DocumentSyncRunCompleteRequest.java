package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record DocumentSyncRunCompleteRequest(
        @NotBlank String previewToken,
        @PositiveOrZero Integer confirmMissingCount) {

    public int effectiveConfirmMissingCount() {
        return confirmMissingCount == null ? -1 : confirmMissingCount;
    }
}
