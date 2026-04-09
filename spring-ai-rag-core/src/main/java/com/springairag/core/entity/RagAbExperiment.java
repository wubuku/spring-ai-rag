package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * A/B test experiment entity.
 *
 * <p>An experiment contains multiple variants, with trafficSplit controlling distribution.
 * State transitions: DRAFT → RUNNING → PAUSED → COMPLETED
 */
@Entity
@Table(name = "rag_ab_experiments", indexes = {
    @Index(name = "idx_rag_ab_exp_status", columnList = "status"),
    @Index(name = "idx_rag_ab_exp_created", columnList = "created_at")
})
public class RagAbExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic locking version field. */
    @Version
    private Long version;

    @Column(name = "experiment_name", nullable = false, unique = true)
    private String experimentName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 50)
    private String status = "DRAFT";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "traffic_split", columnDefinition = "jsonb", nullable = false)
    private Map<String, Double> trafficSplit;

    @Column(name = "target_metric")
    private String targetMetric;

    @Column(name = "start_time")
    private ZonedDateTime startTime;

    @Column(name = "end_time")
    private ZonedDateTime endTime;

    @Column(name = "min_sample_size")
    private Integer minSampleSize = 100;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;

    // ==================== Constructors ====================

    public RagAbExperiment() {
    }

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExperimentName() { return experimentName; }
    public void setExperimentName(String experimentName) { this.experimentName = experimentName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Double> getTrafficSplit() { return trafficSplit; }
    public void setTrafficSplit(Map<String, Double> trafficSplit) { this.trafficSplit = trafficSplit; }
    public String getTargetMetric() { return targetMetric; }
    public void setTargetMetric(String targetMetric) { this.targetMetric = targetMetric; }
    public ZonedDateTime getStartTime() { return startTime; }
    public void setStartTime(ZonedDateTime startTime) { this.startTime = startTime; }
    public ZonedDateTime getEndTime() { return endTime; }
    public void setEndTime(ZonedDateTime endTime) { this.endTime = endTime; }
    public Integer getMinSampleSize() { return minSampleSize; }
    public void setMinSampleSize(Integer minSampleSize) { this.minSampleSize = minSampleSize; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }
}
