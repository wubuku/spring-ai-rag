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
 * 性能优化配置
 *
 * <p>提供：
 * <ul>
 *   <li>缓存嵌入模型 — 包装原始 EmbeddingModel，对相同文本缓存向量结果</li>
 *   <li>检索专用线程池 — 给 HybridRetrieverService 并行向量/全文检索用</li>
 * </ul>
 */
@Configuration
public class PerformanceConfig {

    /**
     * 检索专用线程池
     *
     * <p>固定 4 线程，用于 HybridRetrieverService 的 CompletableFuture 并行检索。
     * 不使用默认 ForkJoinPool，避免与其他异步任务竞争。
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
     * 模型对比专用线程池
     *
     * <p>核心 2 线程、最大 8 线程，支持模型对比场景的动态扩展。
     * 复用线程避免每次对比创建新 ExecutorService（资源泄漏）。
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
     * 缓存嵌入模型 — 包装原始 EmbeddingModel
     *
     * <p>对 embed(text) 调用做 Caffeine 缓存，同一文本 2 小时内不重复调用 API。
     * 通过 @Primary 优先注入，HybridRetrieverService 等自动获得缓存能力。
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
     * 缓存包装的 EmbeddingModel — 带 Micrometer 命中率追踪
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
                    .description("嵌入缓存命中次数")
                    .register(meterRegistry);
            this.missCounter = Counter.builder("rag.cache.embedding.miss")
                    .description("嵌入缓存未命中次数")
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
