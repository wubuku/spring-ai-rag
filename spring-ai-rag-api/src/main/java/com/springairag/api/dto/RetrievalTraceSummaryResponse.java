package com.springairag.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 当前调用方可见的检索诊断列表项。
 */
public record RetrievalTraceSummaryResponse(
        UUID traceId,
        String operation,
        String outcomeCode,
        String emptyReasonCode,
        String sessionId,
        OffsetDateTime createdAt,
        Integer resultCount,
        Long totalTimeMs,
        String citationStatus) {
}
