package com.springairag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.Objects;

/**
 * Cache statistics response.
 *
 * @param hitCount Hit count
 * @param missCount Miss count
 * @param totalCount Total query count
 * @param hitRate Hit rate (percentage string)
 * @param details Raw statistics
 */
@Schema(description = "Embedding cache statistics")
public record CacheStatsResponse(
        @Schema(description = "Number of cache hits", example = "127") long hitCount,
        @Schema(description = "Number of cache misses", example = "43") long missCount,
        @Schema(description = "Total number of cache queries", example = "170") long totalCount,
        @Schema(description = "Cache hit rate as a percentage string", example = "74.7%") String hitRate,
        @Schema(description = "Detailed statistics per collection or content hash") Map<String, Object> details
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof CacheStatsResponse that
                && this.hitCount == that.hitCount
                && this.missCount == that.missCount
                && this.totalCount == that.totalCount
                && Objects.equals(this.hitRate, that.hitRate)
                && Objects.equals(this.details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hitCount, missCount, totalCount, hitRate, details);
    }

    @Override
    public String toString() {
        return "CacheStatsResponse{hitCount=" + hitCount + ", missCount=" + missCount
                + ", totalCount=" + totalCount + ", hitRate=" + hitRate
                + ", detailsSize=" + (details == null ? 0 : details.size()) + "}";
    }
}
