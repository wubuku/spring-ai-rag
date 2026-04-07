package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 文档创建/更新请求
 */
@Schema(description = "文档创建/更新请求")
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

    @Schema(description = "附加元数据（JSON 对象）")
    private Map<String, Object> metadata;

    @Schema(description = "所属知识库 ID（可选，创建时指定）", example = "1")
    private Long collectionId;

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
}
