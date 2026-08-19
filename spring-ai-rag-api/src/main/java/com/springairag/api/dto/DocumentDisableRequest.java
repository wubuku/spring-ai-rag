package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CAS request for disabling a locally managed document.
 */
public class DocumentDisableRequest {

    @NotNull
    @Positive
    private Long expectedDocumentRevision;
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public Long getExpectedDocumentRevision() { return expectedDocumentRevision; }
    public void setExpectedDocumentRevision(Long value) { expectedDocumentRevision = value; }

    @JsonAnySetter
    public void captureUnknown(String name, Object value) {
        unknownFields.add(name);
    }

    public Set<String> getUnknownFieldNames() {
        return Set.copyOf(unknownFields);
    }
}

