package com.springairag.api.dto;

import java.util.Map;

/**
 * Cache statistics response.
 *
 * @param hitCount Hit count
 * @param missCount Miss count
 * @param totalCount Total query count
 * @param hitRate Hit rate (percentage string)
 * @param details Raw statistics
 */
public record CacheStatsResponse(
        long hitCount,
        long missCount,
        long totalCount,
        String hitRate,
        Map<String, Object> details
) {
    public static CacheStatsResponse from(Map<String, Object> stats) {
        return new CacheStatsResponse(
                ((Number) stats.getOrDefault("hitCount", 0L)).longValue(),
                ((Number) stats.getOrDefault("missCount", 0L)).longValue(),
                ((Number) stats.getOrDefault("totalCount", 0L)).longValue(),
                (String) stats.getOrDefault("hitRate", "N/A"),
                stats
        );
    }
}
