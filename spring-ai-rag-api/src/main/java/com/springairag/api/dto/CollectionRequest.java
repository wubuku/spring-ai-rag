package com.springairag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 文档集合（知识库）创建/更新请求
 */
public class CollectionRequest {

    @NotBlank(message = "集合名称不能为空")
    @Size(max = 255, message = "集合名称不超过 255 字符")
    private String name;

    @Size(max = 2000, message = "集合描述不超过 2000 字符")
    private String description;

    private String embeddingModel;

    private Integer dimensions = 1024;

    private Boolean enabled = true;

    private Map<String, Object> metadata;

    public CollectionRequest() {
    }

    // Getters and Setters

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
