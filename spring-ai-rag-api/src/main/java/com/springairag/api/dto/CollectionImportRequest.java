package com.springairag.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.validation.NoNulCharacter;
import com.springairag.api.validation.NoNulInJsonTree;
import com.springairag.api.validation.ValidCollectionKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Typed collection import contract compatible with collection export JSON.
 */
public class CollectionImportRequest {

    @NotBlank
    @Size(max = 255)
    @NoNulCharacter(message = "Collection name must not contain NUL (U+0000) characters")
    private String name;

    @NotBlank
    @ValidCollectionKey
    private String collectionKey;

    @Size(max = 2000)
    @NoNulCharacter(message = "Collection description must not contain NUL (U+0000) characters")
    private String description;
    @Size(max = 255)
    @NoNulCharacter(message = "embeddingModel must not contain NUL (U+0000) characters")
    private String embeddingModel;
    private Integer dimensions;
    private Boolean enabled;
    @NoNulInJsonTree(message = "Collection metadata must not contain NUL (U+0000) characters")
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
        @Size(max = 255, message = "Document title must not exceed 255 characters")
        @NoNulCharacter(message = "Document title must not contain NUL (U+0000) characters")
        private String title;
        @NotBlank
        @Size(max = 1_000_000, message = "Document content must not exceed 1 million characters")
        @NoNulCharacter(message = "Document content must not contain NUL (U+0000) characters")
        private String content;
        @Size(max = 255)
        @NoNulCharacter(message = "Document source must not contain NUL (U+0000) characters")
        private String source;
        @Size(max = 50)
        @NoNulCharacter(message = "Document type must not contain NUL (U+0000) characters")
        private String documentType;
        @NoNulInJsonTree(message = "Document metadata must not contain NUL (U+0000) characters")
        private Map<String, Object> metadata;
        private Long size;
        @Size(max = 255)
        @NoNulCharacter(message = "externalId must not contain NUL (U+0000) characters")
        private String externalId;
        private String sourceNamespace = "default";
        @Size(max = 255)
        @NoNulCharacter(message = "sourceRevision must not contain NUL (U+0000) characters")
        private String sourceRevision;
        private LocalDateTime sourceDeletedAt;
        private JsonNode jsonbPayload;
        @Size(max = 255)
        @NoNulCharacter(message = "originalFilename must not contain NUL (U+0000) characters")
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
        public String getSourceNamespace() { return sourceNamespace; }
        public void setSourceNamespace(String sourceNamespace) {
            this.sourceNamespace = sourceNamespace;
        }
        public String getSourceRevision() { return sourceRevision; }
        public void setSourceRevision(String sourceRevision) { this.sourceRevision = sourceRevision; }
        public LocalDateTime getSourceDeletedAt() { return sourceDeletedAt; }
        public void setSourceDeletedAt(LocalDateTime sourceDeletedAt) {
            this.sourceDeletedAt = sourceDeletedAt;
        }
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
