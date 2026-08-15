package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

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
        String error
) {
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
                embeddingProfileKey, error);
    }
}
