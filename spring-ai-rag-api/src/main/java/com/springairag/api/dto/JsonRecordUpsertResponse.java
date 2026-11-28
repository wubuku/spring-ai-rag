package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Result of one JSON structured-record upsert.
 */
@Schema(description = "JSON structured-record upsert result")
public record JsonRecordUpsertResponse(
        Long documentId,
        @Schema(description = "Deprecated internal Collection ID", deprecated = true)
        Long collectionId,
        @Schema(description = "Stable external Collection key")
        String collectionKey,
        String externalId,
        String action,
        boolean contentChanged,
        boolean payloadChanged,
        int versionNumber,
        String embeddingStatus,
        String embeddingProfileKey,
        String error,
        String embeddingAction,
        UUID embeddingJobId,
        UUID embeddingBatchId,
        String sourceNamespace,
        String sourceRevision,
        Long documentRevision,
        DocumentLifecycleResponse lifecycle,
        @Schema(description = "Content digest of the upserted record, matching RagDocument.contentHash")
        String contentHash
) {
    /** 18 参旧构造器：contentHash 可空，保证历史调用方零编译破坏。 */
    public JsonRecordUpsertResponse(
            Long documentId,
            Long collectionId,
            String collectionKey,
            String externalId,
            String action,
            boolean contentChanged,
            boolean payloadChanged,
            int versionNumber,
            String embeddingStatus,
            String embeddingProfileKey,
            String error,
            String embeddingAction,
            UUID embeddingJobId,
            UUID embeddingBatchId,
            String sourceNamespace,
            String sourceRevision,
            Long documentRevision,
            DocumentLifecycleResponse lifecycle) {
        this(documentId, collectionId, collectionKey, externalId, action,
                contentChanged, payloadChanged, versionNumber,
                embeddingStatus, embeddingProfileKey, error,
                embeddingAction, embeddingJobId, embeddingBatchId,
                sourceNamespace, sourceRevision, documentRevision, lifecycle,
                null);
    }

    public JsonRecordUpsertResponse(
            Long documentId,
            Long collectionId,
            String collectionKey,
            String externalId,
            String action,
            boolean contentChanged,
            boolean payloadChanged,
            int versionNumber,
            String embeddingStatus,
            String embeddingProfileKey,
            String error,
            String embeddingAction,
            UUID embeddingJobId,
            UUID embeddingBatchId) {
        this(documentId, collectionId, collectionKey, externalId, action,
                contentChanged, payloadChanged, versionNumber,
                embeddingStatus, embeddingProfileKey, error,
                embeddingAction, embeddingJobId, embeddingBatchId,
                null, null, null, null);
    }

    public JsonRecordUpsertResponse(
            Long documentId,
            Long collectionId,
            String collectionKey,
            String externalId,
            String action,
            boolean contentChanged,
            boolean payloadChanged,
            int versionNumber,
            String embeddingStatus,
            String embeddingProfileKey,
            String error) {
        this(documentId, collectionId, collectionKey, externalId, action,
                contentChanged, payloadChanged, versionNumber, embeddingStatus,
                embeddingProfileKey, error, null, null, null,
                null, null, null, null);
    }

    public JsonRecordUpsertResponse(
            Long documentId,
            Long collectionId,
            String externalId,
            String action,
            boolean contentChanged,
            boolean payloadChanged,
            int versionNumber,
            String embeddingStatus,
            String embeddingProfileKey,
            String error) {
        this(documentId, collectionId, null, externalId, action, contentChanged,
                payloadChanged, versionNumber, embeddingStatus,
                embeddingProfileKey, error, null, null, null,
                null, null, null, null);
    }
}
