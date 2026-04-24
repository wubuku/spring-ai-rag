package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.Objects;

/**
 * Silence schedule request DTO
 */
@Schema(description = "Silence schedule request")
public class SilenceScheduleRequest {

    @Schema(description = "Silence schedule name (unique)", example = "weekend-maintenance")
    @NotBlank(message = "Name is required")
    private String name;

    @Schema(description = "Alert key to silence (null = all alerts)", example = "high-latency")
    private String alertKey;

    @Schema(description = "Silence type: ONE_TIME or RECURRING", example = "RECURRING")
    @NotBlank(message = "Silence type is required")
    private String silenceType;

    @Schema(description = "Start time: ISO datetime for ONE_TIME or Cron expression for RECURRING", example = "2026-04-10T02:00:00+08:00")
    @NotBlank(message = "Start time is required")
    private String startTime;

    @Schema(description = "End time: ISO datetime for ONE_TIME or Cron expression end for RECURRING", example = "2026-04-10T04:00:00+08:00")
    @NotBlank(message = "End time is required")
    private String endTime;

    @Schema(description = "Description", example = "Scheduled maintenance window")
    private String description;

    @Schema(description = "Whether this schedule is enabled", example = "true")
    private Boolean enabled = true;

    @Schema(description = "Additional metadata")
    private Map<String, Object> metadata;

    // Getters and Setters
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SilenceScheduleRequest that = (SilenceScheduleRequest) o;
        return Objects.equals(name, that.name)
                && Objects.equals(alertKey, that.alertKey)
                && Objects.equals(silenceType, that.silenceType)
                && Objects.equals(startTime, that.startTime)
                && Objects.equals(endTime, that.endTime)
                && Objects.equals(description, that.description)
                && Objects.equals(enabled, that.enabled)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, alertKey, silenceType, startTime, endTime, description, enabled, metadata);
    }

    @Override
    public String toString() {
        return "SilenceScheduleRequest{name=" + name + ", alertKey=" + alertKey
                + ", silenceType=" + silenceType + ", startTime=" + startTime
                + ", endTime=" + endTime + ", description=" + description
                + ", enabled=" + enabled + ", metadata=" + metadata + "}";
    }
}
