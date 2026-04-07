package com.springairag.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache metrics service — tracks embedding cache hit rate
 *
 * <p>Reads data from PerformanceConfig.CachingEmbeddingModel Micrometer counters,
 * providing structured cache statistics.
 *
 * <p>Raw metrics can be queried at `/actuator/metrics/rag.cache.embedding.hit`, etc.,
 * or aggregated statistics via `/api/v1/cache/stats`.
 */
@Service
public class CacheMetricsService {

    private static final Logger log = LoggerFactory.getLogger(CacheMetricsService.class);
    private static final String EMBEDDINGS_CACHE = "embeddings";

    private final Counter hitCounter;
    private final Counter missCounter;
    private final CacheManager cacheManager;

    public CacheMetricsService(MeterRegistry meterRegistry,
                              @Autowired(required = false) CacheManager cacheManager) {
        this.hitCounter = Counter.builder("rag.cache.embedding.hit")
                .description("Embedding cache hit count")
                .register(meterRegistry);
        this.missCounter = Counter.builder("rag.cache.embedding.miss")
                .description("Embedding cache miss count")
                .register(meterRegistry);
        this.cacheManager = cacheManager;
        log.info("CacheMetricsService initialized (cacheManager: {})",
                cacheManager != null ? "available" : "not configured");
    }

    /**
     * Returns the number of cache hits
     */
    public long getHitCount() {
        return (long) hitCounter.count();
    }

    /**
     * Returns the number of cache misses
     */
    public long getMissCount() {
        return (long) missCounter.count();
    }

    /**
     * Returns the total number of cache queries
     */
    public long getTotalCount() {
        return getHitCount() + getMissCount();
    }

    /**
     * Returns the cache hit rate (percentage, 0-100)
     */
    public double getHitRate() {
        long total = getTotalCount();
        return total > 0 ? (double) getHitCount() / total * 100 : 0.0;
    }

    /**
     * Returns the complete cache statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long hits = getHitCount();
        long misses = getMissCount();
        long total = hits + misses;
        stats.put("hitCount", hits);
        stats.put("missCount", misses);
        stats.put("totalCount", total);
        stats.put("hitRate", total > 0 ? String.format("%.1f%%", (double) hits / total * 100) : "N/A");
        return stats;
    }

    /**
     * Clears the embedding cache.
     *
     * <p>After clearing, all subsequent embedding requests will call the embedding model again.
     * Cache hit rate will reset to 0%.
     *
     * @return the number of cache entries cleared (estimated)
     */
    public int clearCache() {
        if (cacheManager == null) {
            log.warn("CacheManager not available, cannot clear cache");
            return 0;
        }
        var cache = cacheManager.getCache(EMBEDDINGS_CACHE);
        if (cache == null) {
            log.info("Embeddings cache not found");
            return 0;
        }
        cache.clear();
        log.info("Embeddings cache cleared successfully");
        return 1;
    }
}
