package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Server-derived document and derived-index lifecycle state.
 */
@Schema(description = "Current document and derived-index lifecycle")
public record DocumentLifecycleResponse(
        String documentState,
        String searchability,
        String localIndexStatus,
        String embeddingStatus,
        String activeEmbeddingProfileKey,
        UUID activeJobId,
        String lastErrorCode,
        String lastError,
        boolean retryable
) {
}

