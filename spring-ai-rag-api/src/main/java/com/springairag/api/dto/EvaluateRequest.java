package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 评估请求 DTO
 */
@Schema(description = "检索效果评估请求")
public class EvaluateRequest {

    @NotBlank(message = "查询文本不能为空")
    @Size(max = 10000, message = "查询文本不能超过 10000 字符")
    @Schema(description = "查询文本", example = "如何配置 Spring AI？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @NotEmpty(message = "检索到的文档 ID 列表不能为空")
    @Schema(description = "检索到的文档 ID 列表（按排名顺序）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> retrievedDocIds;

    @NotEmpty(message = "相关文档 ID 列表不能为空")
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
