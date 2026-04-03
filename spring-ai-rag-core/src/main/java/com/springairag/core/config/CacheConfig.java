package com.springairag.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * 缓存配置 — Caffeine 本地缓存
 *
 * <p>提供以下缓存区域：
 * <ul>
 *   <li>embeddings — 嵌入向量缓存（文本 → 向量），避免重复 API 调用</li>
 *   <li>retrieval — 检索结果缓存（查询 → 结果），减少数据库压力</li>
 * </ul>
 *
 * <p>缓存参数通过 rag.cache.* 配置，支持运行时调整。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private final RagProperties ragProperties;

    public CacheConfig(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * Caffeine 缓存管理器（通用缓存）
     *
     * <p>使用 rag.cache.maximum-size 和 rag.cache.expire-after-write-minutes 配置
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        RagCacheProperties cache = ragProperties.getCache();
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(cache.getMaximumSize())
                .expireAfterWrite(cache.getExpireAfterWriteMinutes(), TimeUnit.MINUTES)
                .recordStats());
        return manager;
    }

    /**
     * 嵌入向量专用缓存管理器 — 更大容量、更长过期时间
     *
     * <p>嵌入向量是无状态的（同一文本始终产生相同向量），可以长时间缓存。
     * 使用 rag.cache.embedding-maximum-size 和 rag.cache.embedding-expire-after-write-hours 配置
     */
    @Bean("embeddingCacheManager")
    public CacheManager embeddingCacheManager() {
        RagCacheProperties cache = ragProperties.getCache();
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(cache.getEmbeddingMaximumSize())
                .expireAfterWrite(cache.getEmbeddingExpireAfterWriteHours(), TimeUnit.HOURS)
                .recordStats());
        return manager;
    }
}
