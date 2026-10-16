package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/** 数据擦除的最小终态结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataErasureResultResponse(
        UUID operationId,
        String status,
        Map<String, Long> completedCounts,
        Map<String, String> stableErrors) {
}
