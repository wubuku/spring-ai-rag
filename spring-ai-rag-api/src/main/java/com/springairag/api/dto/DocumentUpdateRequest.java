package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.validation.NoNulCharacter;
import com.springairag.api.validation.ValidCollectionKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Presence-aware local document merge-patch request.
 */
@Schema(description = "CAS update for a locally managed document")
public class DocumentUpdateRequest {

    @NotNull
    @Positive
    private Long expectedDocumentRevision;
    @Size(max = 255, message = "Document title must not exceed 255 characters")
    @NoNulCharacter(message = "Document title must not contain NUL (U+0000) characters")
    private String title;
    @Size(max = 1_000_000, message = "Document content must not exceed 1 million characters")
    @NoNulCharacter(message = "Document content must not contain NUL (U+0000) characters")
    private String content;
    @Size(max = 255, message = "Document source must not exceed 255 characters")
    @NoNulCharacter(message = "Document source must not contain NUL (U+0000) characters")
    private String source;
    private Map<String, Object> metadata;
    @ValidCollectionKey
    private String collectionKey;
    private EmbeddingPolicy embeddingPolicy = EmbeddingPolicy.ASYNC;

    private boolean titlePresent;
    private boolean contentPresent;
    private boolean sourcePresent;
    private boolean metadataPresent;
    private boolean collectionKeyPresent;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public Long getExpectedDocumentRevision() { return expectedDocumentRevision; }
    public void setExpectedDocumentRevision(Long value) { expectedDocumentRevision = value; }

    public String getTitle() { return title; }
    public void setTitle(String value) {
        titlePresent = true;
        title = value;
    }

    public String getContent() { return content; }
    public void setContent(String value) {
        contentPresent = true;
        content = value;
    }

    public String getSource() { return source; }
    public void setSource(String value) {
        sourcePresent = true;
        source = value;
    }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> value) {
        metadataPresent = true;
        metadata = value;
    }

    public String getCollectionKey() { return collectionKey; }
    public void setCollectionKey(String value) {
        collectionKeyPresent = true;
        collectionKey = value;
    }

    public EmbeddingPolicy getEmbeddingPolicy() { return embeddingPolicy; }
    public void setEmbeddingPolicy(EmbeddingPolicy value) {
        embeddingPolicy = value == null ? EmbeddingPolicy.ASYNC : value;
    }

    public boolean isTitlePresent() { return titlePresent; }
    public boolean isContentPresent() { return contentPresent; }
    public boolean isSourcePresent() { return sourcePresent; }
    public boolean isMetadataPresent() { return metadataPresent; }
    public boolean isCollectionKeyPresent() { return collectionKeyPresent; }
    public boolean hasMutableFields() {
        return titlePresent || contentPresent || sourcePresent
                || metadataPresent || collectionKeyPresent;
    }

    @JsonAnySetter
    public void captureUnknown(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Set<String> getUnknownFieldNames() {
        return Set.copyOf(unknownFields.keySet());
    }
}

