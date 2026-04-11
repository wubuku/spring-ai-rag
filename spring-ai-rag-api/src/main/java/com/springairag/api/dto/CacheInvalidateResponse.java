package com.springairag.api.dto;

import java.util.Map;

/**
 * Cache invalidation response.
 *
 * @param cleared Number of cache entries cleared
 * @param message Human-readable message describing the result
 */
public record CacheInvalidateResponse(
        int cleared,
        String message
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
