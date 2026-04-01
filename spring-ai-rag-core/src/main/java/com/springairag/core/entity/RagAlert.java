package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * RAG 告警实体
 *
 * <p>持久化存储告警记录，支持服务重启后数据不丢失。
 * 告警类型包括阈值告警、SLO 违约告警等。
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

    /** 告警类型：THRESHOLD_HIGH / THRESHOLD_LOW / SLO_BREACH */
    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    /** 告警名称 */
    @Column(name = "alert_name", nullable = false, length = 100)
    private String alertName;

    /** 告警消息 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /** 严重程度：INFO / WARNING / CRITICAL */
    @Column(nullable = false, length = 20)
    private String severity;

    /** 关联的指标数据 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    /** 状态：ACTIVE / RESOLVED / SILENCED */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 解决方案描述 */
    @Column(columnDefinition = "TEXT")
    private String resolution;

    /** 触发时间 */
    @Column(name = "fired_at", nullable = false)
    private ZonedDateTime firedAt;

    /** 解决时间 */
    @Column(name = "resolved_at")
    private ZonedDateTime resolvedAt;

    /** 静默截止时间 */
    @Column(name = "silenced_until")
    private ZonedDateTime silencedUntil;

    /** 创建时间 */
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
