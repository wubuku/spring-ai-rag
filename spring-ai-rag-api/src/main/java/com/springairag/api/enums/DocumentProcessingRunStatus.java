package com.springairag.api.enums;

/** 文档派生处理运行的公开状态。 */
public enum DocumentProcessingRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    STALE,
    DEAD_LETTERED
}
