package com.springairag.api.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 当前调用方可见的检索诊断详情。不含 chunk、payload、Prompt 或 secret。
 */
public record RetrievalTraceDetailResponse(
        UUID traceId,
        String operation,
        String outcomeCode,
        String emptyReasonCode,
        String sessionId,
        OffsetDateTime createdAt,
        Integer resultCount,
        Long totalTimeMs,
        Map<String, Object> resultScores,
        Map<String, Object> metadata,
        String requestTraceId) {

    /** 保留已有 Java 调用方的 10 参数构造签名。 */
    public RetrievalTraceDetailResponse(
            UUID traceId,
            String operation,
            String outcomeCode,
            String emptyReasonCode,
            String sessionId,
            OffsetDateTime createdAt,
            Integer resultCount,
            Long totalTimeMs,
            Map<String, Object> resultScores,
            Map<String, Object> metadata) {
        this(traceId, operation, outcomeCode, emptyReasonCode, sessionId,
                createdAt, resultCount, totalTimeMs, resultScores, metadata, null);
    }
}
