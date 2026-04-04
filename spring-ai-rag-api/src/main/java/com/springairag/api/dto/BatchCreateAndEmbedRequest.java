package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量创建并嵌入文档请求
 *
 * <p>一步到位：创建文档 + 分块 + 嵌入向量。
 */
@Schema(description = "批量创建并嵌入文档请求（一步到位）")
public class BatchCreateAndEmbedRequest {

    @NotNull(message = "collectionId 不能为空")
    @Schema(description = "目标知识库 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long collectionId;

    @NotEmpty(message = "文档列表不能为空")
    @Size(max = 100, message = "单次批量操作不超过 100 条")
    @Valid
    @Schema(description = "文档列表（最多 100 条）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<DocumentRequest> documents;

    @Schema(description = "是否强制重新嵌入（忽略已有嵌入）", example = "false")
    private boolean force = false;

    public BatchCreateAndEmbedRequest() {}

    public BatchCreateAndEmbedRequest(Long collectionId, List<DocumentRequest> documents) {
        this.collectionId = collectionId;
        this.documents = documents;
    }

    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }

    public List<DocumentRequest> getDocuments() { return documents; }
    public void setDocuments(List<DocumentRequest> documents) { this.documents = documents; }

    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
}
