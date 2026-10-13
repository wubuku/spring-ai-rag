package com.springairag.api.dto;

import com.springairag.api.enums.DocumentProcessingRunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文档生命周期中可嵌入的处理运行摘要。 */
public record DocumentProcessingSummary(
        UUID runId,
        DocumentProcessingRunStatus status,
        String activeStage,
        int executionAttempt,
        int maxAttempts,
        OffsetDateTime nextRetryAt,
        boolean retryable,
        boolean deadLettered,
        boolean stageHistoryComplete,
        OffsetDateTime updatedAt,
        String lastErrorCode,
        String lastError) {
}
