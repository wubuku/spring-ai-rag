package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.validation.ValidCollectionKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Collection-scoped JSON structured-record search request.
 */
@Schema(description = "JSON structured-record search request")
public class JsonRecordSearchRequest {

    @NotBlank
    @Size(max = 10000)
    private String query;

    @Size(max = 50)
    @Schema(description = "Deprecated numeric Collection scope. Use collectionKeys.",
            deprecated = true)
    private List<@Positive Long> collectionIds;

    @Size(max = 50)
    @Schema(description = "Required stable external Collection scope (preferred)",
            example = "[\"customer-42:records:v1\"]")
    private List<@ValidCollectionKey String> collectionKeys;

    @Valid
    private RetrievalConfig config;

    @Schema(description = "Optional non-empty JSON object matched against document metadata")
    private JsonNode metadataContains;

    @Schema(description = "Optional non-empty JSON object matched with PostgreSQL JSONB containment")
    private JsonNode payloadContains;

    @Valid
    @Schema(description = "Optional grouped filters. Cannot be combined with top-level metadataContains or payloadContains")
    private RetrievalFilterRequest filters;

    public JsonRecordSearchRequest() {
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<Long> getCollectionIds() {
        return collectionIds;
    }

    public void setCollectionIds(List<Long> collectionIds) {
        this.collectionIds = collectionIds;
    }

    public List<String> getCollectionKeys() {
        return collectionKeys;
    }

    public void setCollectionKeys(List<String> collectionKeys) {
        this.collectionKeys = collectionKeys;
    }

    public RetrievalConfig getConfig() {
        return config;
    }

    public void setConfig(RetrievalConfig config) {
        this.config = config;
    }

    public JsonNode getMetadataContains() {
        return metadataContains;
    }

    public void setMetadataContains(JsonNode metadataContains) {
        this.metadataContains = metadataContains;
    }

    public JsonNode getPayloadContains() {
        return payloadContains;
    }

    public void setPayloadContains(JsonNode payloadContains) {
        this.payloadContains = payloadContains;
    }

    public RetrievalFilterRequest getFilters() {
        return filters;
    }

    public void setFilters(RetrievalFilterRequest filters) {
        this.filters = filters;
    }
}
