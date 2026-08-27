package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * RAG alert entity.
 *
 * <p>Persists alert records so data survives service restarts.
 * Alert types include threshold alerts, SLO breach alerts, etc.
 */
@Entity
@Table(name = "rag_alerts", indexes = {
    @Index(name = "idx_rag_alert_type", columnList = "alert_type"),
    @Index(name = "idx_rag_alert_severity", columnList = "severity"),
    @Index(name = "idx_rag_alert_status", columnList = "status"),
    @Index(name = "idx_rag_alert_fired", columnList = "fired_at")
})
public class RagAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic locking version field. */
    @Version
    private Long version;

    /** 受管条件的内部去重键；不通过 AlertRecord 暴露。 */
    @Column(name = "dedupe_key", length = 160)
    private String dedupeKey;

    /** 受管条件当前阶段。 */
    @Column(name = "condition_state", length = 32)
    private String conditionState;

    @Column(name = "state_version", nullable = false)
    private Integer stateVersion = 0;

    @Column(name = "notified_version", nullable = false)
    private Integer notifiedVersion = 0;

    /** Alert type: THRESHOLD_HIGH / THRESHOLD_LOW / SLO_BREACH */
    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    /** Alert name */
    @Column(name = "alert_name", nullable = false, length = 100)
    private String alertName;

    /** Alert message */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /** Severity: INFO / WARNING / CRITICAL */
    @Column(nullable = false, length = 20)
    private String severity;

    /** Associated metric data */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    /** Status: ACTIVE / RESOLVED / SILENCED */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** Resolution description */
    @Column(columnDefinition = "TEXT")
    private String resolution;

    /** Triggered at */
    @Column(name = "fired_at", nullable = false)
    private ZonedDateTime firedAt;

    /** Resolved at */
    @Column(name = "resolved_at")
    private ZonedDateTime resolvedAt;

    /** Silence deadline */
    @Column(name = "silenced_until")
    private ZonedDateTime silencedUntil;

    /** Created at */
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt = ZonedDateTime.now();

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public String getConditionState() { return conditionState; }
    public void setConditionState(String conditionState) {
        this.conditionState = conditionState;
    }
    public Integer getStateVersion() { return stateVersion; }
    public void setStateVersion(Integer stateVersion) {
        this.stateVersion = stateVersion;
    }
    public Integer getNotifiedVersion() { return notifiedVersion; }
    public void setNotifiedVersion(Integer notifiedVersion) {
        this.notifiedVersion = notifiedVersion;
    }

    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }

    public String getAlertName() { return alertName; }
    public void setAlertName(String alertName) { this.alertName = alertName; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public ZonedDateTime getFiredAt() { return firedAt; }
    public void setFiredAt(ZonedDateTime firedAt) { this.firedAt = firedAt; }

    public ZonedDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(ZonedDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public ZonedDateTime getSilencedUntil() { return silencedUntil; }
    public void setSilencedUntil(ZonedDateTime silencedUntil) { this.silencedUntil = silencedUntil; }

    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }
}
