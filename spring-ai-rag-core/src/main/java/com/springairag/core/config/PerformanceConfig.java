package com.springairag.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance Optimization Configuration
 *
 * <p>Provides:
 * <ul>
 *   <li>Caching EmbeddingModel — wraps the original EmbeddingModel, caches vectors for identical text</li>
 *   <li>Retrieval-dedicated thread pool — for HybridRetrieverService parallel vector/full-text retrieval</li>
 * </ul>
 */
@Configuration
public class PerformanceConfig {

    /**
     * Retrieval-dedicated thread pool
     *
     * <p>Fixed 4 threads, used for HybridRetrieverService CompletableFuture parallel retrieval.
     * Does not use the default ForkJoinPool to avoid competing with other async tasks.
     */
    @Bean("ragSearchExecutor")
    public Executor ragSearchExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "rag-search");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Model comparison dedicated thread pool
     *
     * <p>Core 2 threads, max 8 threads, supports dynamic expansion for model comparison scenarios.
     * Reuses threads to avoid creating a new ExecutorService for each comparison (resource leak).
     */
    @Bean("modelComparisonExecutor")
    public ExecutorService modelComparisonExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(16),
                r -> {
                    Thread t = new Thread(r, "model-compare");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Caching EmbeddingModel — wraps the original EmbeddingModel
     *
     * <p>Caches Caffeine results for embed(text) calls; the same text won't call the API again within the TTL.
     * Marked @Primary so it is injected by preference, giving HybridRetrieverService etc. automatic caching.
     */
    @Bean
    @Primary
    public EmbeddingModel cachedEmbeddingModel(
            EmbeddingModel delegate,
            @Qualifier("embeddingCacheManager") CacheManager embeddingCacheManager,
            MeterRegistry meterRegistry) {

        return new CachingEmbeddingModel(delegate, embeddingCacheManager, meterRegistry);
    }

    /**
     * Cached wrapper for EmbeddingModel — with Micrometer cache hit rate tracking
     */
    static class CachingEmbeddingModel implements EmbeddingModel {

        private final EmbeddingModel delegate;
        private final Cache cache;
        private final Counter hitCounter;
        private final Counter missCounter;
        private final AtomicLong cacheSize;

        CachingEmbeddingModel(EmbeddingModel delegate, CacheManager cacheManager,
                              MeterRegistry meterRegistry) {
            this.delegate = delegate;
            this.cache = cacheManager.getCache("embeddings");
            this.hitCounter = Counter.builder("rag.cache.embedding.hit")
                    .description("Embedding cache hit count")
                    .register(meterRegistry);
            this.missCounter = Counter.builder("rag.cache.embedding.miss")
                    .description("Embedding cache miss count")
                    .register(meterRegistry);
            this.cacheSize = new AtomicLong(0);
            meterRegistry.gauge("rag.cache.embedding.size", cacheSize);
        }

        @Override
        public float[] embed(String text) {
            Float[] cached = cache.get(text, Float[].class);
            if (cached != null) {
                hitCounter.increment();
                float[] result = new float[cached.length];
                for (int i = 0; i < cached.length; i++) {
                    result[i] = cached[i];
                }
                return result;
            }

            missCounter.increment();
            float[] vector = delegate.embed(text);
            Float[] boxed = new Float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                boxed[i] = vector[i];
            }
            cache.put(text, boxed);
            cacheSize.incrementAndGet();
            return vector;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return delegate.call(request);
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }
    }
}
