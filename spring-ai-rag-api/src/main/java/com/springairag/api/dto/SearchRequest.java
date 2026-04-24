package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

/**
 * Search request parameters
 */
@Schema(description = "Search request parameters")
public class SearchRequest {

    @NotBlank(message = "Query text must not be blank")
    @Size(max = 10000, message = "Query text must not exceed 10000 characters")
    @Schema(description = "Query text", example = "What is Spring AI?", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @Schema(description = "Limit to document ID list (empty means search all)", example = "[1, 2, 3]")
    private List<Long> documentIds;

    @Schema(description = "Search within these collection IDs (multi-collection search)", example = "[1, 2, 3]")
    private List<Long> collectionIds;

    @Valid
    @Schema(description = "Retrieval configuration parameters")
    private RetrievalConfig config;

    public SearchRequest() {}

    public SearchRequest(String query) {
        this.query = query;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

    public List<Long> getCollectionIds() { return collectionIds; }
    public void setCollectionIds(List<Long> collectionIds) { this.collectionIds = collectionIds; }

    public RetrievalConfig getConfig() { return config; }
    public void setConfig(RetrievalConfig config) { this.config = config; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchRequest that = (SearchRequest) o;
        return Objects.equals(query, that.query) &&
                Objects.equals(documentIds, that.documentIds) &&
                Objects.equals(collectionIds, that.collectionIds) &&
                Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, documentIds, collectionIds, config);
    }

    @Override
    public String toString() {
        return "SearchRequest{" +
                "query='" + query + '\'' +
                ", documentIds=" + documentIds +
                ", collectionIds=" + collectionIds +
                ", config=" + config +
                '}';
    }
}
