package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * 检索资格过滤。只支持固定 JSONB containment，不是查询语言。
 */
@Schema(description = "Retrieval eligibility filters using JSONB containment")
public class RetrievalFilterRequest {

    @Schema(description = "Non-empty JSON object matched against rag_documents.metadata with PostgreSQL @>")
    private JsonNode metadataContains;

    @Schema(description = "Non-empty JSON object matched against rag_documents.jsonb_payload with PostgreSQL @>")
    private JsonNode payloadContains;

    public RetrievalFilterRequest() {
    }

    public RetrievalFilterRequest(JsonNode metadataContains, JsonNode payloadContains) {
        this.metadataContains = metadataContains;
        this.payloadContains = payloadContains;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RetrievalFilterRequest that = (RetrievalFilterRequest) o;
        return Objects.equals(metadataContains, that.metadataContains)
                && Objects.equals(payloadContains, that.payloadContains);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadataContains, payloadContains);
    }
}
