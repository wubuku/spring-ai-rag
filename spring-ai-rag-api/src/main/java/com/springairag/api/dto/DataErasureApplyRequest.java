package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 应用已经确认的数据擦除预览。 */
public record DataErasureApplyRequest(
        @NotNull UUID operationId,
        @NotBlank String confirmationToken,
        @NotBlank String fingerprint) {
}
