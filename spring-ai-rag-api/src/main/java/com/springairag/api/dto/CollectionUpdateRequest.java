package com.springairag.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Mutable fields for updating an existing Collection.
 *
 * <p>The immutable external {@code collectionKey} is intentionally absent.
 */
@Schema(description = "Collection update request; collectionKey is immutable and cannot be updated")
public class CollectionUpdateRequest {

    private boolean collectionKeySupplied;

    @Size(max = 255, message = "Collection name must not exceed 255 characters")
    @Schema(description = "Collection name", example = "My Knowledge Base")
    private String name;

    @Schema(description = "Collection description")
    private String description;

    @Schema(description = "Embedding model")
    private String embeddingModel;

    @Schema(description = "Vector dimensions", example = "1024")
    private Integer dimensions;

    @Schema(description = "Whether the collection is enabled")
    private Boolean enabled;

    @Schema(description = "Additional metadata")
    private Map<String, Object> metadata;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer dimensions) { this.dimensions = dimensions; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /**
     * Captures an immutable key in the JSON payload without exposing it as an
     * updateable OpenAPI property.
     */
    @JsonSetter("collectionKey")
    @Schema(hidden = true)
    public void captureCollectionKey(Object ignored) {
        collectionKeySupplied = true;
    }

    @JsonIgnore
    @Schema(hidden = true)
    public void rejectImmutableCollectionKey() {
        if (collectionKeySupplied) {
            throw new IllegalArgumentException(
                    "collectionKey is immutable and must not be supplied when updating");
        }
    }
}
