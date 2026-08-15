package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Typed collection import contract compatible with collection export JSON.
 */
public class CollectionImportRequest {

    @NotBlank
    private String name;

    @NotBlank
    @ValidCollectionKey
    private String collectionKey;

    private String description;
    private String embeddingModel;
    private Integer dimensions;
    private Boolean enabled;
    private Map<String, Object> metadata;

    @Valid
    private List<ImportedDocument> documents;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCollectionKey() { return collectionKey; }
    public void setCollectionKey(String collectionKey) { this.collectionKey = collectionKey; }
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
    public List<ImportedDocument> getDocuments() { return documents; }
    public void setDocuments(List<ImportedDocument> documents) { this.documents = documents; }

    public static class ImportedDocument {
        @NotBlank
        private String title;
        @NotBlank
        private String content;
        private String source;
        private String documentType;
        private Map<String, Object> metadata;
        private Long size;
        private String externalId;
        private JsonNode jsonbPayload;
        private String originalFilename;
        private Boolean enabled;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
        public Long getSize() { return size; }
        public void setSize(Long size) { this.size = size; }
        public String getExternalId() { return externalId; }
        public void setExternalId(String externalId) { this.externalId = externalId; }
        public JsonNode getJsonbPayload() { return jsonbPayload; }
        public void setJsonbPayload(JsonNode jsonbPayload) { this.jsonbPayload = jsonbPayload; }
        public String getOriginalFilename() { return originalFilename; }
        public void setOriginalFilename(String originalFilename) {
            this.originalFilename = originalFilename;
        }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
