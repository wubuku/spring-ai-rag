package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 评估请求 DTO
 */
@Schema(description = "检索效果评估请求")
public class EvaluateRequest {

    @Schema(description = "查询文本", example = "如何配置 Spring AI？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @Schema(description = "检索到的文档 ID 列表（按排名顺序）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> retrievedDocIds;

    @Schema(description = "相关文档 ID 列表（Ground Truth）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> relevantDocIds;

    @Schema(description = "评估方法", example = "AUTO")
    private String evaluationMethod = "AUTO";

    @Schema(description = "评估人 ID")
    private String evaluatorId;

    public EvaluateRequest() {
    }

    public EvaluateRequest(String query, List<Long> retrievedDocIds, List<Long> relevantDocIds) {
        this.query = query;
        this.retrievedDocIds = retrievedDocIds;
        this.relevantDocIds = relevantDocIds;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<Long> getRetrievedDocIds() { return retrievedDocIds; }
    public void setRetrievedDocIds(List<Long> retrievedDocIds) { this.retrievedDocIds = retrievedDocIds; }
    public List<Long> getRelevantDocIds() { return relevantDocIds; }
    public void setRelevantDocIds(List<Long> relevantDocIds) { this.relevantDocIds = relevantDocIds; }
    public String getEvaluationMethod() { return evaluationMethod; }
    public void setEvaluationMethod(String evaluationMethod) { this.evaluationMethod = evaluationMethod; }
    public String getEvaluatorId() { return evaluatorId; }
    public void setEvaluatorId(String evaluatorId) { this.evaluatorId = evaluatorId; }
}
