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
 * Cache Configuration — Caffeine Local Cache
 *
 * <p>Provides the following cache regions:
 * <ul>
 *   <li>embeddings — Embedding vector cache (text → vector), avoids repeated API calls</li>
 *   <li>retrieval — Retrieval result cache (query → results), reduces database pressure</li>
 * </ul>
 *
 * <p>Cache parameters are configured via rag.cache.*, supporting runtime adjustment.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private final RagProperties ragProperties;

    public CacheConfig(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * Caffeine CacheManager (general cache)
     *
     * <p>Configured using rag.cache.maximum-size and rag.cache.expire-after-write-minutes
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
     * Embedding vector dedicated CacheManager — larger capacity, longer TTL
     *
     * <p>Embedding vectors are stateless (the same text always produces the same vector) and can be cached for longer.
     * Configured using rag.cache.embedding-maximum-size and rag.cache.embedding-expire-after-write-hours
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
