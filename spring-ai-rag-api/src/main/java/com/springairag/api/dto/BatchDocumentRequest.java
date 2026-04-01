package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量文档操作请求
 */
@Schema(description = "批量文档操作请求")
public class BatchDocumentRequest {

    @NotEmpty(message = "文档列表不能为空")
    @Size(max = 100, message = "单次批量操作不超过 100 条")
    @Valid
    @Schema(description = "文档列表（最多 100 条）")
    private List<DocumentRequest> documents;

    public BatchDocumentRequest() {}

    public BatchDocumentRequest(List<DocumentRequest> documents) {
        this.documents = documents;
    }

    public List<DocumentRequest> getDocuments() { return documents; }
    public void setDocuments(List<DocumentRequest> documents) { this.documents = documents; }
}
