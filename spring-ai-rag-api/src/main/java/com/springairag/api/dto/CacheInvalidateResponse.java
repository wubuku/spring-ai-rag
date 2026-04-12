package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Cache invalidation response.
 *
 * @param cleared Number of cache entries cleared
 * @param message Human-readable message describing the result
 */
@Schema(description = "Cache invalidation result")
public record CacheInvalidateResponse(
        @Schema(description = "Number of cache entries cleared", example = "5") int cleared,
        @Schema(description = "Human-readable result message", example = "Cache invalidated") String message
) {
    public static CacheInvalidateResponse from(int cleared) {
        return new CacheInvalidateResponse(
                cleared,
                cleared > 0 ? "Cache invalidated" : "No entries to clear"
        );
    }

    public static CacheInvalidateResponse from(Map<String, Object> result) {
        int cleared = ((Number) result.getOrDefault("cleared", 0)).intValue();
        String message = (String) result.getOrDefault("message", "");
        return new CacheInvalidateResponse(cleared, message);
    }
}
