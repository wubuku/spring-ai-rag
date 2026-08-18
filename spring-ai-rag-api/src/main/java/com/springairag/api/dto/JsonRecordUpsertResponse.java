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
        UUID embeddingBatchId
) {
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
                embeddingProfileKey, error, null, null, null);
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
                embeddingProfileKey, error, null, null, null);
    }
}
