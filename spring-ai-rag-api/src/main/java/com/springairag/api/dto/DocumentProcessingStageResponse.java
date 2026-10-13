package com.springairag.api.dto;

import com.springairag.api.enums.DocumentProcessingStageName;
import com.springairag.api.enums.DocumentProcessingStageStatus;

import java.time.OffsetDateTime;

/** 单个文档处理阶段的无敏感内容快照。 */
public record DocumentProcessingStageResponse(
        DocumentProcessingStageName stageName,
        int stageOrder,
        DocumentProcessingStageStatus status,
        int executionAttempt,
        int progressCurrent,
        int progressTotal,
        String progressLabel,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime lastHeartbeatAt,
        OffsetDateTime updatedAt,
        String lastErrorCode,
        String lastError) {
}
