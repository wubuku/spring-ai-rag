package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

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
}
