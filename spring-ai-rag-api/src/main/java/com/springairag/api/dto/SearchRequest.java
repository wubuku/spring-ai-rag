package com.springairag.api.dto;

import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.api.validation.ValidCollectionKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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

    @Size(max = 1000, message = "At most 1000 document IDs may be selected")
    @Schema(description = "Limit to document ID list (empty means search all)", example = "[1, 2, 3]")
    private List<@Positive(message = "Document IDs must be positive") Long> documentIds;

    @Schema(description = "Collection retrieval scope. Omitted preserves compatibility: "
            + "Collection fields imply SELECTED_COLLECTIONS; otherwise CALLER_VISIBLE.",
            example = "SELECTED_COLLECTIONS")
    private CollectionScopeMode collectionScopeMode;

    @Size(max = 100, message = "At most 100 collection IDs may be selected")
    @Schema(description = "Deprecated compatibility field. Use collectionKeys.",
            example = "[1, 2, 3]", deprecated = true)
    private List<@Positive(message = "Collection IDs must be positive") Long> collectionIds;

    @Size(max = 100, message = "At most 100 collection keys may be selected")
    @Schema(description = "Stable external Collection keys (preferred over collectionIds)", example = "[\"customer-42:manual:v3\"]")
    private List<@ValidCollectionKey String> collectionKeys;

    @Valid
    @Schema(description = "Retrieval configuration parameters")
    private RetrievalConfig config;

    @Valid
    @Schema(description = "Optional metadata/payload containment filters")
    private RetrievalFilterRequest filters;

    public SearchRequest() {}

    public SearchRequest(String query) {
        this.query = query;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

    public CollectionScopeMode getCollectionScopeMode() { return collectionScopeMode; }
    public void setCollectionScopeMode(CollectionScopeMode collectionScopeMode) {
        this.collectionScopeMode = collectionScopeMode;
    }

    public List<Long> getCollectionIds() { return collectionIds; }
    public void setCollectionIds(List<Long> collectionIds) { this.collectionIds = collectionIds; }

    public List<String> getCollectionKeys() { return collectionKeys; }
    public void setCollectionKeys(List<String> collectionKeys) { this.collectionKeys = collectionKeys; }

    public RetrievalConfig getConfig() { return config; }
    public void setConfig(RetrievalConfig config) { this.config = config; }

    public RetrievalFilterRequest getFilters() { return filters; }
    public void setFilters(RetrievalFilterRequest filters) { this.filters = filters; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchRequest that = (SearchRequest) o;
        return Objects.equals(query, that.query) &&
                Objects.equals(documentIds, that.documentIds) &&
                collectionScopeMode == that.collectionScopeMode &&
                Objects.equals(collectionIds, that.collectionIds) &&
                Objects.equals(collectionKeys, that.collectionKeys) &&
                Objects.equals(config, that.config) &&
                Objects.equals(filters, that.filters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, documentIds, collectionScopeMode,
                collectionIds, collectionKeys, config, filters);
    }

    @Override
    public String toString() {
        return "SearchRequest{" +
                "query='" + query + '\'' +
                ", documentIds=" + documentIds +
                ", collectionScopeMode=" + collectionScopeMode +
                ", collectionIds=" + collectionIds +
                ", collectionKeys=" + collectionKeys +
                ", config=" + config +
                ", filters=" + filters +
                '}';
    }
}
