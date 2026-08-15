package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Current JSON structured-record detail.
 */
@Schema(description = "JSON structured-record detail response")
public record JsonRecordDetailResponse(
        Long documentId,
        @Schema(description = "Deprecated internal Collection ID", deprecated = true)
        Long collectionId,
        @Schema(description = "Stable external Collection key")
        String collectionKey,
        String externalId,
        String title,
        String source,
        String retrievalText,
        JsonNode jsonbPayload,
        String contentHash,
        String processingStatus,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int versionNumber,
        Map<String, Object> metadata
) {
    public JsonRecordDetailResponse(
            Long documentId,
            Long collectionId,
            String externalId,
            String title,
            String source,
            String retrievalText,
            JsonNode jsonbPayload,
            String contentHash,
            String processingStatus,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            int versionNumber,
            Map<String, Object> metadata) {
        this(documentId, collectionId, null, externalId, title, source,
                retrievalText, jsonbPayload, contentHash, processingStatus,
                enabled, createdAt, updatedAt, versionNumber, metadata);
    }
}
