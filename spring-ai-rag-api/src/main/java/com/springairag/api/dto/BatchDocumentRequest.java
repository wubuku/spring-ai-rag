package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量文档操作请求
 *
 * <p>支持两种模式：仅创建（默认）和创建并嵌入（embed=true）。
 */
@Schema(description = "批量文档操作请求")
public class BatchDocumentRequest {

    @NotEmpty(message = "Document list must not be empty")
    @Size(max = 100, message = "Batch operation must not exceed 100 items")
    @Valid
    @Schema(description = "List of documents (max 100)")
    private List<DocumentRequest> documents;

    @Schema(description = "Whether to auto-embed vectors after creation (default false: create documents only)", example = "false")
    private boolean embed = false;

    @Schema(description = "Associated collection ID (only effective when embed=true)", example = "1")
    private Long collectionId;

    @Schema(description = "Whether to force re-embedding (only effective when embed=true, true=ignore existing embeddings and regenerate)", example = "false")
    private boolean force = false;

    public BatchDocumentRequest() {}

    public BatchDocumentRequest(List<DocumentRequest> documents) {
        this.documents = documents;
    }

    public List<DocumentRequest> getDocuments() { return documents; }
    public void setDocuments(List<DocumentRequest> documents) { this.documents = documents; }
    public boolean isEmbed() { return embed; }
    public void setEmbed(boolean embed) { this.embed = embed; }
    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
}
