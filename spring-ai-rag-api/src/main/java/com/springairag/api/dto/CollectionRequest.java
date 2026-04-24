package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Objects;

/**
 * Collection (knowledge base) creation/update request
 */
@Schema(description = "Collection (knowledge base) creation/update request")
public class CollectionRequest {

    @NotBlank(message = "Collection name is required")
    @Size(max = 255, message = "Collection name must not exceed 255 characters")
    @Schema(description = "Collection name", example = "My Knowledge Base", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    @Schema(description = "Collection description", example = "Company product documentation and FAQs")
    private String description;

    @Schema(description = "Embedding model name (defaults to configured model)", example = "BAAI/bge-m3")
    private String embeddingModel;

    @Schema(description = "Embedding vector dimensions (must match the embedding model)", example = "1024", defaultValue = "1024")
    private Integer dimensions = 1024;

    @Schema(description = "Whether the collection is active for retrieval", example = "true", defaultValue = "true")
    private Boolean enabled = true;

    @Schema(description = "Additional metadata as key-value pairs")
    private Map<String, Object> metadata;

    public CollectionRequest() {
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionRequest that = (CollectionRequest) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(description, that.description) &&
                Objects.equals(embeddingModel, that.embeddingModel) &&
                Objects.equals(dimensions, that.dimensions) &&
                Objects.equals(enabled, that.enabled) &&
                Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, embeddingModel, dimensions, enabled, metadata);
    }

    @Override
    public String toString() {
        return "CollectionRequest{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", embeddingModel='" + embeddingModel + '\'' +
                ", dimensions=" + dimensions +
                ", enabled=" + enabled +
                ", metadata=" + metadata +
                '}';
    }
}
