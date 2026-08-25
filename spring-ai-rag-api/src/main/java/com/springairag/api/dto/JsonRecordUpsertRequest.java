package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.validation.ValidCollectionKey;
import com.springairag.api.validation.ValidSourceNamespace;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.springairag.api.enums.EmbeddingPolicy;

import java.util.Map;

/**
 * Caller-supplied JSON structured-record upsert request.
 */
@Schema(description = "JSON structured-record upsert request")
public class JsonRecordUpsertRequest {

    @Positive
    @Schema(description = "Deprecated target Collection ID. Use collectionKey.",
            deprecated = true)
    private Long collectionId;

    @ValidCollectionKey
    @Schema(description = "Stable external target Collection key (preferred)",
            example = "customer-42:records:v1")
    private String collectionKey;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Caller-supplied stable record identity", requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalId;

    @Size(max = 128)
    @ValidSourceNamespace
    @Schema(description = "External connector identity namespace", defaultValue = "default")
    private String sourceNamespace = "default";

    @Size(max = 255)
    @Schema(description = "Opaque source revision token")
    private String sourceRevision;

    @Size(max = 255)
    @Schema(description = "Expected current source revision for compare-and-set")
    private String expectedSourceRevision;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Record title", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank
    @Size(max = 1_000_000)
    @Schema(description = "Caller-supplied natural-language retrieval description",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String retrievalText;

    @NotNull
    @Schema(description = "Business JSON payload; it is stored as JSONB and is not embedded",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private JsonNode jsonbPayload;

    @Size(max = 255)
    private String source;

    private Map<String, Object> metadata;

    @Schema(description = "Generate embedding after persistence", defaultValue = "true")
    private boolean embed = true;

    @Schema(description = "Authoritative embedding policy. When omitted, embed=true maps to SYNC")
    private EmbeddingPolicy embeddingPolicy;

    public JsonRecordUpsertRequest() {
    }

    public Long getCollectionId() {
        return collectionId;
    }

    public void setCollectionId(Long collectionId) {
        this.collectionId = collectionId;
    }

    public String getCollectionKey() {
        return collectionKey;
    }

    public void setCollectionKey(String collectionKey) {
        this.collectionKey = collectionKey;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getSourceNamespace() { return sourceNamespace; }
    public void setSourceNamespace(String sourceNamespace) {
        this.sourceNamespace = sourceNamespace;
    }

    public String getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(String sourceRevision) {
        this.sourceRevision = sourceRevision;
    }

    public String getExpectedSourceRevision() { return expectedSourceRevision; }
    public void setExpectedSourceRevision(String expectedSourceRevision) {
        this.expectedSourceRevision = expectedSourceRevision;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRetrievalText() {
        return retrievalText;
    }

    public void setRetrievalText(String retrievalText) {
        this.retrievalText = retrievalText;
    }

    public JsonNode getJsonbPayload() {
        return jsonbPayload;
    }

    public void setJsonbPayload(JsonNode jsonbPayload) {
        this.jsonbPayload = jsonbPayload;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public boolean isEmbed() {
        return embed;
    }

    public void setEmbed(boolean embed) {
        this.embed = embed;
    }

    public EmbeddingPolicy getEmbeddingPolicy() {
        return embeddingPolicy;
    }

    public void setEmbeddingPolicy(EmbeddingPolicy embeddingPolicy) {
        this.embeddingPolicy = embeddingPolicy;
    }
}
