package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheConfig 单元测试
 */
class CacheConfigTest {

    private final CacheConfig config = new CacheConfig(new RagProperties());

    @Test
    @DisplayName("默认缓存管理器创建成功")
    void cacheManagerCreated() {
        assertNotNull(config.cacheManager());
    }

    @Test
    @DisplayName("嵌入缓存管理器创建成功")
    void embeddingCacheManagerCreated() {
        assertNotNull(config.embeddingCacheManager());
    }

    @Test
    @DisplayName("两个缓存管理器是不同实例")
    void twoCacheManagersAreDifferent() {
        assertNotSame(config.cacheManager(), config.embeddingCacheManager());
    }

    @Test
    @DisplayName("自定义配置生效")
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
