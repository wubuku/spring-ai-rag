package com.springairag.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheConfig 单元测试
 */
class CacheConfigTest {

    @Test
    @DisplayName("默认缓存管理器创建成功")
    void cacheManagerCreated() {
        CacheConfig config = new CacheConfig();
        assertNotNull(config.cacheManager());
    }

    @Test
    @DisplayName("嵌入缓存管理器创建成功")
    void embeddingCacheManagerCreated() {
        CacheConfig config = new CacheConfig();
        assertNotNull(config.embeddingCacheManager());
    }

    @Test
    @DisplayName("两个缓存管理器是不同实例")
    void twoCacheManagersAreDifferent() {
        CacheConfig config = new CacheConfig();
        assertNotSame(config.cacheManager(), config.embeddingCacheManager());
    }
}
