package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Component detailed health check response.
 *
 * @param status Overall status (UP/DEGRADED/DOWN)
 * @param timestamp Check timestamp
 * @param components Detailed component status (name -> full status info)
 */
@Schema(description = "Detailed component-level health check response")
public record ComponentHealthResponse(
        @Schema(description = "Overall health status", example = "UP") String status,
        @Schema(description = "ISO-8601 timestamp of the health check", example = "2026-04-12T07:20:00Z") String timestamp,
        @Schema(description = "Per-component health status map (component name -> status details)") Map<String, Map<String, Object>> components
) {
    public static ComponentHealthResponse of(String status,
            Map<String, Map<String, Object>> components) {
        return new ComponentHealthResponse(status, Instant.now().toString(), components);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ComponentHealthResponse that = (ComponentHealthResponse) o;
        return Objects.equals(status, that.status)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(components, that.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, timestamp, components);
    }
}
