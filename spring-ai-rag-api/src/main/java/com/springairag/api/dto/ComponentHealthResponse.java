package com.springairag.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Component detailed health check response.
 *
 * @param status Overall status (UP/DEGRADED/DOWN)
 * @param timestamp Check timestamp
 * @param components Detailed component status (name -> full status info)
 */
public record ComponentHealthResponse(
        String status,
        String timestamp,
        Map<String, Map<String, Object>> components
) {
    public static ComponentHealthResponse of(String status,
            Map<String, Map<String, Object>> components) {
        return new ComponentHealthResponse(status, Instant.now().toString(), components);
    }
}
