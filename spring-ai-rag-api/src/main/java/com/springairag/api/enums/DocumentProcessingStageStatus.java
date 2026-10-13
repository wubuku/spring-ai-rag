package com.springairag.api.enums;

/** 文档派生处理阶段的公开状态。 */
public enum DocumentProcessingStageStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    STALE
}
