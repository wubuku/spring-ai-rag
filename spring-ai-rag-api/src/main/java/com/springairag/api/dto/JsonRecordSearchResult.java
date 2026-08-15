package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Ranked JSON structured-record search result.
 */
@Schema(description = "JSON structured-record search result")
public record JsonRecordSearchResult(
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
        double score,
        double vectorScore,
        double fulltextScore,
        Map<String, Object> metadata
) {
    public JsonRecordSearchResult(
            Long documentId,
            Long collectionId,
            String externalId,
            String title,
            String source,
            String retrievalText,
            JsonNode jsonbPayload,
            double score,
            double vectorScore,
            double fulltextScore,
            Map<String, Object> metadata) {
        this(documentId, collectionId, null, externalId, title, source,
                retrievalText, jsonbPayload, score, vectorScore,
                fulltextScore, metadata);
    }
}
