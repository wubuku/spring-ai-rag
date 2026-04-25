package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Health check response.
 *
 * @param status Overall status (UP/DEGRADED/DOWN)
 * @param timestamp Check timestamp
 * @param components Component status summary (name -> status string)
 */
@Schema(description = "Service health check response")
public record HealthResponse(
        @Schema(description = "Overall health status", example = "UP") String status,
        @Schema(description = "ISO-8601 timestamp of the health check", example = "2026-04-12T07:20:00Z") String timestamp,
        @Schema(description = "Per-component health status (component name -> status string)") Map<String, String> components
) {
    public static HealthResponse of(String status, Map<String, String> components) {
        return new HealthResponse(status, Instant.now().toString(), components);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthResponse that = (HealthResponse) o;
        return Objects.equals(status, that.status)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(components, that.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, timestamp, components);
    }

    @Override
    public String toString() {
        return "HealthResponse{" +
                "status='" + status + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", components=" + components +
                '}';
    }
}
