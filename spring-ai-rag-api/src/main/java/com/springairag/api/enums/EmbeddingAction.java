package com.springairag.api.enums;

/**
 * 一次导入/嵌入请求实际采取的嵌入动作。
 */
public enum EmbeddingAction {
    SYNC_COMPLETED,
    SYNC_CACHED,
    ASYNC_QUEUED,
    ASYNC_COALESCED,
    SKIPPED
}
