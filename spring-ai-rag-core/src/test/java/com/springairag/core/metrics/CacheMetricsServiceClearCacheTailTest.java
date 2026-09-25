package com.springairag.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheMetricsService 缓存清理长尾（Batch 656，JaCoCo 驱动）：
 * 无 CacheManager、缓存缺失与缓存命中三条 clearCache 路径。
 */
class CacheMetricsServiceClearCacheTailTest {

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void clearCacheWithoutManagerReturnsZero() {
        CacheMetricsService service =
                new CacheMetricsService(meterRegistry, null);

        assertEquals(0, service.clearCache());
    }

    @Test
    void clearCacheWithMissingCacheReturnsZero() {
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache("embeddings")).thenReturn(null);
        CacheMetricsService service =
                new CacheMetricsService(meterRegistry, cacheManager);

        assertEquals(0, service.clearCache());

        verify(cacheManager).getCache("embeddings");
    }

    @Test
    void clearCacheWithExistingCacheClearsAndReports() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("embeddings")).thenReturn(cache);
        CacheMetricsService service =
                new CacheMetricsService(meterRegistry, cacheManager);

        assertEquals(1, service.clearCache());

        verify(cache).clear();
        verify(cacheManager, never()).getCache("other");
    }
}
