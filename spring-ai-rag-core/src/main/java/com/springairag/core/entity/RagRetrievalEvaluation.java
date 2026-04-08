package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Retrieval evaluation record entity.
 *
 * <p>Records retrieval effectiveness metrics per evaluation, including Precision@K, Recall@K, MRR, NDCG, Hit Rate, etc.
 * Used for continuous monitoring and optimization of retrieval quality.
 */
@Entity
@Table(name = "rag_retrieval_evaluations", indexes = {
    @Index(name = "idx_rag_eval_method", columnList = "evaluation_method"),
    @Index(name = "idx_rag_eval_created", columnList = "created_at")
})
public class RagRetrievalEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Query text */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** Expected relevant document IDs (JSON) */
    @Column(name = "expected_document_ids", columnDefinition = "TEXT")
    private String expectedDocumentIds;

    /** Actually retrieved document IDs (JSON) */
    @Column(name = "retrieved_document_ids", columnDefinition = "TEXT")
    private String retrievedDocumentIds;

    /** Evaluation result details (JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_result", columnDefinition = "jsonb")
    private Map<String, Object> evaluationResult;

    /** Precision@K (K → value) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "precision_at_k", columnDefinition = "jsonb")
    private Map<Integer, Double> precisionAtK;

    /** Recall@K (K → value) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recall_at_k", columnDefinition = "jsonb")
    private Map<Integer, Double> recallAtK;

    /** Mean Reciprocal Rank */
    private Double mrr;

    /** Normalized Discounted Cumulative Gain */
    private Double ndcg;

    /** Hit Rate (whether at least one relevant doc is in top-K) */
    @Column(name = "hit_rate")
    private Double hitRate;

    /** Evaluation method: AUTO / MANUAL / LLM */
    @Column(name = "evaluation_method", length = 50)
    private String evaluationMethod;

    /** Evaluator ID */
    @Column(name = "evaluator_id")
    private String evaluatorId;

    /** Extension fields */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Created at */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    // ==================== Constructors ====================

    public RagRetrievalEvaluation() {
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getExpectedDocumentIds() {
        return expectedDocumentIds;
    }

    public void setExpectedDocumentIds(String expectedDocumentIds) {
        this.expectedDocumentIds = expectedDocumentIds;
    }

    public String getRetrievedDocumentIds() {
        return retrievedDocumentIds;
    }

    public void setRetrievedDocumentIds(String retrievedDocumentIds) {
        this.retrievedDocumentIds = retrievedDocumentIds;
    }

    public Map<String, Object> getEvaluationResult() {
        return evaluationResult;
    }

    public void setEvaluationResult(Map<String, Object> evaluationResult) {
        this.evaluationResult = evaluationResult;
    }

    public Map<Integer, Double> getPrecisionAtK() {
        return precisionAtK;
    }

    public void setPrecisionAtK(Map<Integer, Double> precisionAtK) {
        this.precisionAtK = precisionAtK;
    }

    public Map<Integer, Double> getRecallAtK() {
        return recallAtK;
    }

    public void setRecallAtK(Map<Integer, Double> recallAtK) {
        this.recallAtK = recallAtK;
    }

    public Double getMrr() {
        return mrr;
    }

    public void setMrr(Double mrr) {
        this.mrr = mrr;
    }

    public Double getNdcg() {
        return ndcg;
    }

    public void setNdcg(Double ndcg) {
        this.ndcg = ndcg;
    }

    public Double getHitRate() {
        return hitRate;
    }

    public void setHitRate(Double hitRate) {
        this.hitRate = hitRate;
    }

    public String getEvaluationMethod() {
        return evaluationMethod;
    }

    public void setEvaluationMethod(String evaluationMethod) {
        this.evaluationMethod = evaluationMethod;
    }

    public String getEvaluatorId() {
        return evaluatorId;
    }

    public void setEvaluatorId(String evaluatorId) {
        this.evaluatorId = evaluatorId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
