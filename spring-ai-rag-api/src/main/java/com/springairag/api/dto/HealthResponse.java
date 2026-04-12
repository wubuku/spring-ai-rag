package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

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
}
