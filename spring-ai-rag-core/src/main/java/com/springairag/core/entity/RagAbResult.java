package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * A/B 测试结果实体
 *
 * <p>记录单次实验请求的检索指标。每个 sessionId + experimentId 组合只记录一次，
 * 避免重复统计。
 */
@Entity
@Table(name = "rag_ab_results")
public class RagAbResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id", nullable = false)
    private RagAbExperiment experiment;

    @Column(name = "variant_name", nullable = false)
    private String variantName;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    @Column(name = "retrieved_document_ids", columnDefinition = "TEXT")
    private String retrievedDocumentIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> metrics;

    @Column(name = "is_converted")
    private Boolean isConverted;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    // ==================== Constructors ====================

    public RagAbResult() {
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public RagAbExperiment getExperiment() { return experiment; }
    public void setExperiment(RagAbExperiment experiment) { this.experiment = experiment; }
    public String getVariantName() { return variantName; }
    public void setVariantName(String variantName) { this.variantName = variantName; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getRetrievedDocumentIds() { return retrievedDocumentIds; }
    public void setRetrievedDocumentIds(String retrievedDocumentIds) { this.retrievedDocumentIds = retrievedDocumentIds; }
    public Map<String, Double> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Double> metrics) { this.metrics = metrics; }
    public Boolean getIsConverted() { return isConverted; }
    public void setIsConverted(Boolean isConverted) { this.isConverted = isConverted; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
}
