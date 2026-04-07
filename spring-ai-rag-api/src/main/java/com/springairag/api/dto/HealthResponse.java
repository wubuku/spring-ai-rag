package com.springairag.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Health check response.
 *
 * @param status Overall status (UP/DEGRADED/DOWN)
 * @param timestamp Check timestamp
 * @param components Component status summary (name -> status string)
 */
public record HealthResponse(
        String status,
        String timestamp,
        Map<String, String> components
) {
    public static HealthResponse of(String status, Map<String, String> components) {
        return new HealthResponse(status, Instant.now().toString(), components);
    }
}
