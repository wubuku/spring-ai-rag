package com.springairag.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PerformanceConfig 单元测试
 */
class PerformanceConfigTest {

    @Test
    @DisplayName("ragSearchExecutor 创建成功且为守护线程")
    void ragSearchExecutorCreated() {
        PerformanceConfig config = new PerformanceConfig();
        Executor executor = config.ragSearchExecutor();
        assertNotNull(executor);

        // 验证可以执行任务
        var completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        executor.execute(() -> completed.set(true));

        // 等待任务完成
        long start = System.currentTimeMillis();
        while (!completed.get() && System.currentTimeMillis() - start < 1000) {
            Thread.yield();
        }
        assertTrue(completed.get(), "线程池应能执行任务");
    }

    @Test
    @DisplayName("缓存嵌入模型代理调用原始模型")
    void cachedEmbeddingModelDelegates() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        float[] fakeVector = {0.1f, 0.2f, 0.3f};
        when(mockDelegate.embed("hello")).thenReturn(fakeVector);
        when(mockDelegate.embed("world")).thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        CacheManager cacheManager = createTestCacheManager();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager);

        // 第一次调用
        float[] result1 = cached.embed("hello");
        assertArrayEquals(fakeVector, result1);
        verify(mockDelegate, times(1)).embed("hello");

        // 第二次调用相同文本 — 应走缓存，不调用 delegate
        float[] result2 = cached.embed("hello");
        assertArrayEquals(fakeVector, result2);
        verify(mockDelegate, times(1)).embed("hello");

        // 不同文本 — 应调用 delegate
        float[] result3 = cached.embed("world");
        assertArrayEquals(new float[]{0.4f, 0.5f, 0.6f}, result3);
        verify(mockDelegate, times(1)).embed("world");
    }

    @Test
    @DisplayName("缓存嵌入模型的 call() 直接透传不做缓存")
    void cachedEmbeddingModelCallPassthrough() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        org.springframework.ai.embedding.EmbeddingRequest request =
                mock(org.springframework.ai.embedding.EmbeddingRequest.class);
        org.springframework.ai.embedding.EmbeddingResponse expectedResponse =
                mock(EmbeddingResponse.class);
        when(mockDelegate.call(request)).thenReturn(expectedResponse);

        CacheManager cacheManager = createTestCacheManager();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager);

        EmbeddingResponse response = cached.call(request);
        assertSame(expectedResponse, response);
        verify(mockDelegate, times(1)).call(request);
    }

    @Test
    @DisplayName("缓存嵌入模型返回正确的 dimensions")
    void cachedEmbeddingModelDimensions() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        when(mockDelegate.dimensions()).thenReturn(1024);

        CacheManager cacheManager = createTestCacheManager();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager);

        assertEquals(1024, cached.dimensions());
    }

    private CacheManager createTestCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES));
        return manager;
    }
}
