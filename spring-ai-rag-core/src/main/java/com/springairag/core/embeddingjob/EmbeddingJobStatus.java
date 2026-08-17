package com.springairag.core.embeddingjob;

/**
 * 持久化 embedding job 状态。
 */
public enum EmbeddingJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    STALE
}
