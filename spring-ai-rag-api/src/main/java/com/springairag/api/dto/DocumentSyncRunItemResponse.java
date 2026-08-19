package com.springairag.api.dto;

import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;

import java.util.UUID;

public record DocumentSyncRunItemResponse(
        String externalId,
        DocumentSyncDocumentKind documentKind,
        DocumentSyncItemStatus status,
        Long documentId,
        String sourceRevision,
        String errorCode,
        String error,
        String embeddingAction,
        UUID embeddingJobId) {
}
