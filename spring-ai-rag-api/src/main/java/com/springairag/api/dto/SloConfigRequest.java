package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * SLO configuration request DTO
 */
@Schema(description = "SLO configuration request")
public class SloConfigRequest {

    @Schema(description = "SLO name (unique)", example = "availability_p99")
    @NotBlank(message = "SLO name is required")
    private String sloName;

    @Schema(description = "SLO type: AVAILABILITY / LATENCY / QUALITY / ERROR_RATE", example = "LATENCY")
    @NotBlank(message = "SLO type is required")
    private String sloType;

    @Schema(description = "Target value", example = "200.0")
    @NotNull(message = "Target value is required")
    private Double targetValue;

    @Schema(description = "Unit: ms / % / score", example = "ms")
    @NotBlank(message = "Unit is required")
    private String unit;

    @Schema(description = "Description", example = "P99 latency should be under 200ms")
    private String description;

    @Schema(description = "Whether this SLO is enabled", example = "true")
    private Boolean enabled = true;

    @Schema(description = "Additional metadata")
    private Map<String, Object> metadata;

    // Getters and Setters
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SloConfigRequest that = (SloConfigRequest) o;
        return Objects.equals(sloName, that.sloName)
                && Objects.equals(sloType, that.sloType)
                && Objects.equals(targetValue, that.targetValue)
                && Objects.equals(unit, that.unit)
                && Objects.equals(description, that.description)
                && Objects.equals(enabled, that.enabled)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sloName, sloType, targetValue, unit, description, enabled, metadata);
    }

    @Override
    public String toString() {
        return "SloConfigRequest{sloName=" + sloName + ", sloType=" + sloType
                + ", targetValue=" + targetValue + ", unit=" + unit
                + ", description=" + description + ", enabled=" + enabled
                + ", metadata=" + metadata + "}";
    }
}
