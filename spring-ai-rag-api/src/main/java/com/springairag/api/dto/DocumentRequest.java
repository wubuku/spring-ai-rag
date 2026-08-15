package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.springairag.api.validation.ValidCollectionKey;
import java.util.Map;
import java.util.Objects;

/**
 * Document create/update request.
 */
@Schema(description = "Document create/update request")
public class DocumentRequest {

    @NotBlank(message = "Document title must not be blank")
    @Size(max = 500, message = "Document title must not exceed 500 characters")
    @Schema(description = "Document title", example = "Product Manual", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank(message = "Document content must not be blank")
    @Size(max = 1_000_000, message = "Document content must not exceed 1 million characters")
    @Schema(description = "Document body content", example = "This document describes how to use the product...", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Size(max = 255, message = "Document source must not exceed 255 characters")
    @Schema(description = "Document source identifier", example = "manual-upload")
    private String source;

    @Size(max = 50, message = "Document type must not exceed 50 characters")
    @Schema(description = "Document type", example = "markdown")
    private String documentType;

    @Schema(description = "Additional metadata (JSON object)")
    private Map<String, Object> metadata;

    @Schema(description = "Deprecated compatibility field. Use collectionKey.",
            example = "1", deprecated = true)
    private Long collectionId;

    @ValidCollectionKey
    @Schema(description = "Stable external Collection key (preferred over collectionId)", example = "customer-42:manual:v3")
    private String collectionKey;

    public DocumentRequest() {}

    public DocumentRequest(String title, String content) {
        this.title = title;
        this.content = content;
    }

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

    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }

    public String getCollectionKey() { return collectionKey; }
    public void setCollectionKey(String collectionKey) { this.collectionKey = collectionKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentRequest that = (DocumentRequest) o;
        return Objects.equals(title, that.title) &&
                Objects.equals(content, that.content) &&
                Objects.equals(source, that.source) &&
                Objects.equals(documentType, that.documentType) &&
                Objects.equals(metadata, that.metadata) &&
                Objects.equals(collectionId, that.collectionId) &&
                Objects.equals(collectionKey, that.collectionKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, content, source, documentType, metadata, collectionId, collectionKey);
    }

    @Override
    public String toString() {
        return "DocumentRequest{" +
                "title='" + title + '\'' +
                ", content='" + (content != null && content.length() > 50
                        ? content.substring(0, 50) + "..." : content) + '\'' +
                ", source='" + source + '\'' +
                ", documentType='" + documentType + '\'' +
                ", metadata=" + metadata +
                ", collectionId=" + collectionId +
                ", collectionKey='" + collectionKey + '\'' +
                '}';
    }
}
