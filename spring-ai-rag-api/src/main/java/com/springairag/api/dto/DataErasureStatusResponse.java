package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 数据擦除操作的低敏、可恢复状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataErasureStatusResponse(
        UUID operationId,
        String status,
        Map<String, Long> previewCounts,
        Map<String, Long> completedCounts,
        List<String> remainingCategories,
        Map<String, String> stableErrors,
        OffsetDateTime previewExpiresAt,
        OffsetDateTime operationExpiresAt) {
}
