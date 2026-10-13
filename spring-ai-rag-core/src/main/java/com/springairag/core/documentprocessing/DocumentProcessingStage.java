package com.springairag.core.documentprocessing;

import com.springairag.api.enums.DocumentProcessingStageName;
import com.springairag.api.enums.DocumentProcessingStageStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文档派生处理阶段的数据库快照。 */
public record DocumentProcessingStage(
        UUID runId,
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
