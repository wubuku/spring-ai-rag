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

    // ==================== Getters and Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
}
