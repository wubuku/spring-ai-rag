package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

/**
 * Retrieval evaluation request DTO
 */
@Schema(description = "Retrieval evaluation request")
public class EvaluateRequest {

    @NotBlank(message = "Query text must not be blank")
    @Size(max = 10000, message = "Query text must not exceed 10000 characters")
    @Schema(description = "Query text", example = "How to configure Spring AI?", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @NotEmpty(message = "Retrieved document ID list must not be empty")
    @Schema(description = "Retrieved document ID list (in ranking order)", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> retrievedDocIds;

    @NotEmpty(message = "Relevant document ID list must not be empty")
    @Schema(description = "Relevant document ID list (Ground Truth)", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> relevantDocIds;

    @Schema(description = "Evaluation method", example = "AUTO")
    private String evaluationMethod = "AUTO";

    @Schema(description = "Evaluator ID")
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EvaluateRequest that = (EvaluateRequest) o;
        return Objects.equals(query, that.query) &&
                Objects.equals(retrievedDocIds, that.retrievedDocIds) &&
                Objects.equals(relevantDocIds, that.relevantDocIds) &&
                Objects.equals(evaluationMethod, that.evaluationMethod) &&
                Objects.equals(evaluatorId, that.evaluatorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, retrievedDocIds, relevantDocIds, evaluationMethod, evaluatorId);
    }

    @Override
    public String toString() {
        return "EvaluateRequest{" +
                "query='" + query + '\'' +
                ", retrievedDocIds=" + retrievedDocIds +
                ", relevantDocIds=" + relevantDocIds +
                ", evaluationMethod='" + evaluationMethod + '\'' +
                ", evaluatorId='" + evaluatorId + '\'' +
                '}';
    }
}
