package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.EmbeddingPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record DocumentSyncRunItemRequest(
        @NotNull DocumentSyncDocumentKind documentKind,
        @NotBlank @Size(max = 255) String externalId,
        @NotBlank @Size(max = 255) String sourceRevision,
        @Size(max = 255) String title,
        @Size(max = 1_000_000) String content,
        @Size(max = 1_000_000) String retrievalText,
        JsonNode jsonbPayload,
        @Size(max = 255) String source,
        @Size(max = 50) String documentType,
        Map<String, Object> metadata,
        EmbeddingPolicy embeddingPolicy) {

    public EmbeddingPolicy effectiveEmbeddingPolicy() {
        return embeddingPolicy == null ? EmbeddingPolicy.ASYNC : embeddingPolicy;
    }
}
