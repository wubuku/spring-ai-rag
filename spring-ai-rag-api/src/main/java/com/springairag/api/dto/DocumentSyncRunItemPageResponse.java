package com.springairag.api.dto;

import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.DocumentSyncRunStatus;

import java.util.List;
import java.util.UUID;

/**
 * 持久化 Sync Run item receipt 的有界游标页。
 */
public record DocumentSyncRunItemPageResponse(
        UUID runId,
        DocumentSyncRunStatus runStatus,
        DocumentSyncItemStatus statusFilter,
        List<DocumentSyncRunItemReceiptResponse> items,
        DocumentSyncRunItemCurrentSummary currentSummary,
        int limit,
        boolean hasMore,
        String nextCursor) {
}
