package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 长任务的稳定、脱敏客户端回执。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LongRunningOperationResponse(
        UUID operationId,
        String operationKind,
        String resourceType,
        Long collectionId,
        String collectionKey,
        String status,
        Map<String, Object> progress,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String statusUrl,
        String resultUrl,
        String targetProfileKey,
        OffsetDateTime nextRetryAt,
        Integer retryAfterSeconds,
        Boolean cancelRequested,
        Map<String, Object> failure) {
}
