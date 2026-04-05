package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 告警静默计划实体
 *
 * <p>定义告警静默时段，支持：
 * <ul>
 *   <li>一次性静默：在指定时间范围内静默告警</li>
 *   <li>周期性静默：按 Cron 表达式在指定时段静默告警</li>
 * </ul>
 */
@Entity
@Table(name = "rag_silence_schedules", indexes = {
    @Index(name = "idx_silence_enabled", columnList = "enabled"),
    @Index(name = "idx_silence_alert_key", columnList = "alert_key")
})
public class RagSilenceSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 静默名称 */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    /** 关联的告警键（null 表示所有告警） */
    @Column(name = "alert_key", length = 100)
    private String alertKey;

    /** 静默类型：ONE_TIME / RECURRING */
    @Column(name = "silence_type", nullable = false, length = 20)
    private String silenceType;

    /** 开始时间（一次性静默）或 Cron 表达式（周期性静默） */
    @Column(name = "start_time", length = 100)
    private String startTime;

    /** 结束时间（一次性静默）或 Cron 表达式结束时间（周期性静默） */
    @Column(name = "end_time", length = 100)
    private String endTime;

    /** 描述 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 扩展元数据 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAlertKey() { return alertKey; }
    public void setAlertKey(String alertKey) { this.alertKey = alertKey; }

    public String getSilenceType() { return silenceType; }
    public void setSilenceType(String silenceType) { this.silenceType = silenceType; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

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
