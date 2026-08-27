package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * Collection 永久清理确认请求。
 */
public record CollectionPurgeApplyRequest(
        @NotBlank String collectionKey,
        @NotNull UUID previewId,
        @NotBlank String confirmationToken,
        @NotBlank String fingerprint,
        @NotNull @PositiveOrZero Long expectedCollectionVersion,
        @NotNull @PositiveOrZero Long expectedChatCommitFenceVersion) {
}
