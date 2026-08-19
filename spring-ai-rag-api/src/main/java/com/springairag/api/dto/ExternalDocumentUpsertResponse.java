package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Result of an external document upsert.
 */
@Schema(description = "External document upsert result")
public record ExternalDocumentUpsertResponse(
        Long documentId,
        String collectionKey,
        String externalId,
        String sourceRevision,
        String action,
        boolean contentChanged,
        int versionNumber,
        String embeddingStatus,
        String embeddingProfileKey,
        boolean embeddingFresh,
        String processingStatus,
        LocalDateTime sourceDeletedAt,
        String errorCode,
        String error,
        String embeddingAction,
        UUID embeddingJobId,
        UUID embeddingBatchId,
        String sourceNamespace,
        Long documentRevision,
        DocumentLifecycleResponse lifecycle
) {
    public ExternalDocumentUpsertResponse(
            Long documentId,
            String collectionKey,
            String externalId,
            String sourceRevision,
            String action,
            boolean contentChanged,
            int versionNumber,
            String embeddingStatus,
            String embeddingProfileKey,
            boolean embeddingFresh,
            String processingStatus,
            LocalDateTime sourceDeletedAt,
            String errorCode,
            String error,
            String embeddingAction,
            UUID embeddingJobId,
            UUID embeddingBatchId) {
        this(documentId, collectionKey, externalId, sourceRevision, action,
                contentChanged, versionNumber, embeddingStatus,
                embeddingProfileKey, embeddingFresh, processingStatus,
                sourceDeletedAt, errorCode, error, embeddingAction,
                embeddingJobId, embeddingBatchId, null, null, null);
    }

    public ExternalDocumentUpsertResponse(
            Long documentId,
            String collectionKey,
            String externalId,
            String sourceRevision,
            String action,
            boolean contentChanged,
            int versionNumber,
            String embeddingStatus,
            String embeddingProfileKey,
            boolean embeddingFresh,
            String processingStatus,
            LocalDateTime sourceDeletedAt,
            String errorCode,
            String error) {
        this(documentId, collectionKey, externalId, sourceRevision, action,
                contentChanged, versionNumber, embeddingStatus, embeddingProfileKey,
                embeddingFresh, processingStatus, sourceDeletedAt, errorCode, error,
                null, null, null, null, null, null);
    }
}
