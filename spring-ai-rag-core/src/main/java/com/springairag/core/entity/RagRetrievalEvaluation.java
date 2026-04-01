package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 检索评估记录实体
 *
 * <p>记录每次检索效果评估的数据，包括 Precision@K、Recall@K、MRR、NDCG、Hit Rate 等指标。
 * 用于检索质量的持续监控和优化。
 */
@Entity
@Table(name = "rag_retrieval_evaluations")
public class RagRetrievalEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 查询文本 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** 期望相关文档 ID 列表（JSON） */
    @Column(name = "expected_document_ids", columnDefinition = "TEXT")
    private String expectedDocumentIds;

    /** 实际检索到的文档 ID 列表（JSON） */
    @Column(name = "retrieved_document_ids", columnDefinition = "TEXT")
    private String retrievedDocumentIds;

    /** 评估结果详情（JSON） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_result", columnDefinition = "jsonb")
    private Map<String, Object> evaluationResult;

    /** Precision@K（K → 值） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "precision_at_k", columnDefinition = "jsonb")
    private Map<Integer, Double> precisionAtK;

    /** Recall@K（K → 值） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recall_at_k", columnDefinition = "jsonb")
    private Map<Integer, Double> recallAtK;

    /** Mean Reciprocal Rank */
    private Double mrr;

    /** Normalized Discounted Cumulative Gain */
    private Double ndcg;

    /** Hit Rate（top-K 中是否命中至少一个相关文档） */
    @Column(name = "hit_rate")
    private Double hitRate;

    /** 评估方法：AUTO / MANUAL / LLM */
    @Column(name = "evaluation_method", length = 50)
    private String evaluationMethod;

    /** 评估人 ID */
    @Column(name = "evaluator_id")
    private String evaluatorId;

    /** 扩展字段 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** 创建时间 */
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
