package com.springairag.api.dto;

/**
 * 当前 Sync Run item ledger 的状态分布。
 */
public record DocumentSyncRunItemCurrentSummary(
        long total,
        long applied,
        long unchanged,
        long skippedNewerMutation,
        long failed) {
}
