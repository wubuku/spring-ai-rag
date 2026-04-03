package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 检索请求参数
 */
@Schema(description = "检索请求参数")
public class SearchRequest {

    @NotBlank(message = "查询文本不能为空")
    @Size(max = 10000, message = "查询文本不能超过 10000 字符")
    @Schema(description = "查询文本", example = "Spring AI 是什么？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @Schema(description = "限定文档 ID 列表（为空则检索全部）", example = "[1, 2, 3]")
    private List<Long> documentIds;

    @Valid
    @Schema(description = "检索配置参数")
    private RetrievalConfig config;

    public SearchRequest() {}

    public SearchRequest(String query) {
        this.query = query;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

    public RetrievalConfig getConfig() { return config; }
    public void setConfig(RetrievalConfig config) { this.config = config; }
}
