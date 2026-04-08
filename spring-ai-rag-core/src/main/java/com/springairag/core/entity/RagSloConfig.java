package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * SLO configuration entity.
 *
 * <p>Defines Service Level Objectives (SLOs) used for alert triggering.
 * Supports availability, latency, and quality dimensions.
 */
@Entity
@Table(name = "rag_slo_configs", indexes = {
    @Index(name = "idx_rag_slo_type", columnList = "slo_type"),
    @Index(name = "idx_rag_slo_enabled", columnList = "enabled")
})
public class RagSloConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SLO name, unique identifier */
    @Column(name = "slo_name", nullable = false, unique = true, length = 100)
    private String sloName;

    /** SLO type: AVAILABILITY / LATENCY / QUALITY / ERROR_RATE */
    @Column(name = "slo_type", nullable = false, length = 50)
    private String sloType;

    /** Target value */
    @Column(name = "target_value", nullable = false)
    private Double targetValue;

    /** Unit: ms / % / score */
    @Column(nullable = false, length = 20)
    private String unit;

    /** Description */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Whether enabled */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** Extended metadata */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSloName() { return sloName; }
    public void setSloName(String sloName) { this.sloName = sloName; }

    public String getSloType() { return sloType; }
    public void setSloType(String sloType) { this.sloType = sloType; }

    public Double getTargetValue() { return targetValue; }
    public void setTargetValue(Double targetValue) { this.targetValue = targetValue; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }

    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }
}
