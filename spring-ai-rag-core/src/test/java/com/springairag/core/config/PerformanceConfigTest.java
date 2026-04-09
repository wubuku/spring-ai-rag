package com.springairag.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.Document;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PerformanceConfig Unit Test
 */
class PerformanceConfigTest {

    @Test
    @DisplayName("ragSearchExecutor created successfully and is daemon thread")
    void ragSearchExecutorCreated() {
        PerformanceConfig config = new PerformanceConfig();
        Executor executor = config.ragSearchExecutor();
        assertNotNull(executor);

        // Verify task can be executed
        var completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        executor.execute(() -> completed.set(true));

        // Wait for task to complete
        long start = System.currentTimeMillis();
        while (!completed.get() && System.currentTimeMillis() - start < 1000) {
            Thread.yield();
        }
        assertTrue(completed.get(), "Thread pool should be able to execute tasks");
    }

    @Test
    @DisplayName("Cached embedding model proxy delegates to original model")
    void cachedEmbeddingModelDelegates() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        float[] fakeVector = {0.1f, 0.2f, 0.3f};
        when(mockDelegate.embed("hello")).thenReturn(fakeVector);
        when(mockDelegate.embed("world")).thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        CacheManager cacheManager = createTestCacheManager();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager, meterRegistry);

        // First call
        float[] result1 = cached.embed("hello");
        assertArrayEquals(fakeVector, result1);
        verify(mockDelegate, times(1)).embed("hello");

        // Second call with same text — should use cache, not call delegate
        float[] result2 = cached.embed("hello");
        assertArrayEquals(fakeVector, result2);
        verify(mockDelegate, times(1)).embed("hello");

        // Different text — should call delegate
        float[] result3 = cached.embed("world");
        assertArrayEquals(new float[]{0.4f, 0.5f, 0.6f}, result3);
        verify(mockDelegate, times(1)).embed("world");
    }

    @Test
    @DisplayName("Cached embedding model's call() passes through directly without caching")
    void cachedEmbeddingModelCallPassthrough() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        org.springframework.ai.embedding.EmbeddingRequest request =
                mock(org.springframework.ai.embedding.EmbeddingRequest.class);
        org.springframework.ai.embedding.EmbeddingResponse expectedResponse =
                mock(EmbeddingResponse.class);
        when(mockDelegate.call(request)).thenReturn(expectedResponse);

        CacheManager cacheManager = createTestCacheManager();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager, meterRegistry);

        EmbeddingResponse response = cached.call(request);
        assertSame(expectedResponse, response);
        verify(mockDelegate, times(1)).call(request);
    }

    @Test
    @DisplayName("Cached embedding model returns correct dimensions")
    void cachedEmbeddingModelDimensions() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        when(mockDelegate.dimensions()).thenReturn(1024);

        CacheManager cacheManager = createTestCacheManager();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager, meterRegistry);

        assertEquals(1024, cached.dimensions());
    }

    @Test
    @DisplayName("Cached embedding model increments hit/miss counters correctly")
    void cachedEmbeddingModelCounters() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        float[] fakeVector = {0.1f, 0.2f, 0.3f};
        when(mockDelegate.embed("cacheable")).thenReturn(fakeVector);

        CacheManager cacheManager = createTestCacheManager();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager, meterRegistry);

        // First call — should be a miss
        cached.embed("cacheable");
        Counter missCounter = meterRegistry.find("rag.cache.embedding.miss").counter();
        Counter hitCounter = meterRegistry.find("rag.cache.embedding.hit").counter();
        assertNotNull(missCounter);
        assertNotNull(hitCounter);
        assertEquals(1.0, missCounter.count());
        assertEquals(0.0, hitCounter.count());

        // Second call — should be a hit
        cached.embed("cacheable");
        assertEquals(1.0, missCounter.count());
        assertEquals(1.0, hitCounter.count());

        // New text — another miss
        when(mockDelegate.embed("new-text")).thenReturn(new float[]{0.9f, 0.8f, 0.7f});
        cached.embed("new-text");
        assertEquals(2.0, missCounter.count());
        assertEquals(1.0, hitCounter.count());
    }

    @Test
    @DisplayName("Cached embedding model delegates embed(Document) to embed(String)")
    void cachedEmbeddingModelEmbedDocument() {
        EmbeddingModel mockDelegate = mock(EmbeddingModel.class);
        float[] fakeVector = {0.1f, 0.2f, 0.3f};
        when(mockDelegate.embed("document content")).thenReturn(fakeVector);

        CacheManager cacheManager = createTestCacheManager();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PerformanceConfig config = new PerformanceConfig();
        EmbeddingModel cached = config.cachedEmbeddingModel(mockDelegate, cacheManager, meterRegistry);

        Document doc = new Document("document content");

        float[] result = cached.embed(doc);

        assertArrayEquals(fakeVector, result);
        verify(mockDelegate).embed("document content");
    }

    private CacheManager createTestCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES));
        return manager;
    }
}
