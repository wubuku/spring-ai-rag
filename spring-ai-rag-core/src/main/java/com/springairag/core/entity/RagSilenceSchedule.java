package com.springairag.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Alert silence schedule entity.
 *
 * <p>Defines alert silence periods, supporting:
 * <ul>
 *   <li>One-time silence: silence alerts within a specified time range</li>
 *   <li>Recurring silence: silence alerts on a cron schedule</li>
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

    /** Silence name */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    /** Associated alert key (null = all alerts) */
    @Column(name = "alert_key", length = 100)
    private String alertKey;

    /** Silence type: ONE_TIME / RECURRING */
    @Column(name = "silence_type", nullable = false, length = 20)
    private String silenceType;

    /** Start time (one-time) or cron expression (recurring) */
    @Column(name = "start_time", length = 100)
    private String startTime;

    /** End time (one-time) or cron expression end time (recurring) */
    @Column(name = "end_time", length = 100)
    private String endTime;

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
