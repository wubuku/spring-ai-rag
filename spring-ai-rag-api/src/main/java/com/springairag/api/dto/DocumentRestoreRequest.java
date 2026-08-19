package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.springairag.api.enums.EmbeddingPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CAS request for restoring a disabled locally managed document.
 */
public class DocumentRestoreRequest {

    @NotNull
    @Positive
    private Long expectedDocumentRevision;
    private EmbeddingPolicy embeddingPolicy = EmbeddingPolicy.ASYNC;
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public Long getExpectedDocumentRevision() { return expectedDocumentRevision; }
    public void setExpectedDocumentRevision(Long value) { expectedDocumentRevision = value; }

    public EmbeddingPolicy getEmbeddingPolicy() { return embeddingPolicy; }
    public void setEmbeddingPolicy(EmbeddingPolicy value) {
        embeddingPolicy = value == null ? EmbeddingPolicy.ASYNC : value;
    }

    @JsonAnySetter
    public void captureUnknown(String name, Object value) {
        unknownFields.add(name);
    }

    public Set<String> getUnknownFieldNames() {
        return Set.copyOf(unknownFields);
    }
}

