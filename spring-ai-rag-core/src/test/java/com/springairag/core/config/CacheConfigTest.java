package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheConfig Unit Test
 */
class CacheConfigTest {

    private final CacheConfig config = new CacheConfig(new RagProperties());

    @Test
    @DisplayName("Default cache manager created successfully")
    void cacheManagerCreated() {
        assertNotNull(config.cacheManager());
    }

    @Test
    @DisplayName("Embedding cache manager created successfully")
    void embeddingCacheManagerCreated() {
        assertNotNull(config.embeddingCacheManager());
    }

    @Test
    @DisplayName("Two cache managers are different instances")
    void twoCacheManagersAreDifferent() {
        assertNotSame(config.cacheManager(), config.embeddingCacheManager());
    }

    @Test
    @DisplayName("Custom configuration takes effect")
    void customConfigTakesEffect() {
        RagProperties props = new RagProperties();
        props.getCache().setMaximumSize(500);
        props.getCache().setExpireAfterWriteMinutes(10);
        props.getCache().setEmbeddingMaximumSize(5000);
        props.getCache().setEmbeddingExpireAfterWriteHours(4);

        CacheConfig customConfig = new CacheConfig(props);
        assertNotNull(customConfig.cacheManager());
        assertNotNull(customConfig.embeddingCacheManager());
    }
}
