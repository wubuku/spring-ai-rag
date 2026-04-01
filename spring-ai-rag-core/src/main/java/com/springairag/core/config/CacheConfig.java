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
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Caffeine 缓存管理器
     *
     * <p>默认策略：最多 2000 条，写入后 30 分钟过期，记录统计信息
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats());
        return manager;
    }

    /**
     * 嵌入向量专用缓存管理器 — 更大容量、更长过期时间
     *
     * <p>嵌入向量是无状态的（同一文本始终产生相同向量），可以长时间缓存
     */
    @Bean("embeddingCacheManager")
    public CacheManager embeddingCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .recordStats());
        return manager;
    }
}
