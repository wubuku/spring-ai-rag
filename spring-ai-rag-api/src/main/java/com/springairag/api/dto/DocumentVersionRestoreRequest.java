package com.springairag.api.dto;

import com.springairag.api.enums.DocumentRestoreVisibilityMode;
import com.springairag.api.enums.EmbeddingPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * CAS request for restoring a complete local document version.
 */
public record DocumentVersionRestoreRequest(
        @NotNull @Positive Long expectedDocumentRevision,
        EmbeddingPolicy embeddingPolicy,
        DocumentRestoreVisibilityMode visibilityMode) {

    public EmbeddingPolicy effectiveEmbeddingPolicy() {
        return embeddingPolicy == null ? EmbeddingPolicy.ASYNC : embeddingPolicy;
    }

    public DocumentRestoreVisibilityMode effectiveVisibilityMode() {
        return visibilityMode == null
                ? DocumentRestoreVisibilityMode.KEEP_CURRENT
                : visibilityMode;
    }
}
