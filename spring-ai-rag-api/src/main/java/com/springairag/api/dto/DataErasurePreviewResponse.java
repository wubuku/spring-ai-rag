package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 不含原文的数据擦除预览。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataErasurePreviewResponse(
        UUID operationId,
        String status,
        Map<String, Long> previewCounts,
        String confirmationToken,
        String fingerprint,
        OffsetDateTime previewExpiresAt,
        OffsetDateTime operationExpiresAt) {
}
