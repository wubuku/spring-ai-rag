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

    @NotEmpty(message = "文档列表不能为空")
    @Size(max = 100, message = "单次批量操作不超过 100 条")
    @Valid
    @Schema(description = "文档列表（最多 100 条）")
    private List<DocumentRequest> documents;

    @Schema(description = "是否在创建后自动嵌入向量（默认 false，仅创建文档）", example = "false")
    private boolean embed = false;

    @Schema(description = "关联的知识库 ID（仅在 embed=true 时生效）", example = "1")
    private Long collectionId;

    @Schema(description = "是否强制重嵌入（仅在 embed=true 时生效，true=忽略已有嵌入重新生成）", example = "false")
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
