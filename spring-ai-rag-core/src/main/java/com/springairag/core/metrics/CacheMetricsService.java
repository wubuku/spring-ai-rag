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
 * 缓存指标服务 — 追踪嵌入缓存命中率
 *
 * <p>从 PerformanceConfig.CachingEmbeddingModel 的 Micrometer 计数器读取数据，
 * 提供结构化的缓存统计信息。
 *
 * <p>可通过 `/actuator/metrics/rag.cache.embedding.hit` 等端点查询原始指标，
 * 或通过 `/api/v1/cache/stats` 获取汇总统计。
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
                .description("嵌入缓存命中次数")
                .register(meterRegistry);
        this.missCounter = Counter.builder("rag.cache.embedding.miss")
                .description("嵌入缓存未命中次数")
                .register(meterRegistry);
        this.cacheManager = cacheManager;
        log.info("CacheMetricsService initialized (cacheManager: {})",
                cacheManager != null ? "available" : "not configured");
    }

    /**
     * 获取缓存命中次数
     */
    public long getHitCount() {
        return (long) hitCounter.count();
    }

    /**
     * 获取缓存未命中次数
     */
    public long getMissCount() {
        return (long) missCounter.count();
    }

    /**
     * 获取总查询次数
     */
    public long getTotalCount() {
        return getHitCount() + getMissCount();
    }

    /**
     * 获取缓存命中率（百分比，0-100）
     */
    public double getHitRate() {
        long total = getTotalCount();
        return total > 0 ? (double) getHitCount() / total * 100 : 0.0;
    }

    /**
     * 获取完整的缓存统计信息
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
     * 清除嵌入缓存。
     *
     * <p>清除后，所有后续嵌入请求将重新调用嵌入模型，
     * 缓存命中率将重置为 0%。
     *
     * @return 清除的缓存条目数量（估算）
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
