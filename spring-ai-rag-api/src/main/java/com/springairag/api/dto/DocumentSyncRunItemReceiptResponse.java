package com.springairag.api.dto;

import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;

import java.time.OffsetDateTime;

/**
 * Sync Run item ledger 的低敏持久化回执。
 */
public record DocumentSyncRunItemReceiptResponse(
        String externalId,
        DocumentSyncDocumentKind documentKind,
        DocumentSyncItemStatus status,
        Long documentId,
        String sourceRevision,
        String errorCode,
        String error,
        OffsetDateTime seenAt) {
}
