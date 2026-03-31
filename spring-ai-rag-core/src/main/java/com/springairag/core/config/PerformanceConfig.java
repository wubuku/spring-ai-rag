package com.springairag.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
     * 缓存嵌入模型 — 包装原始 EmbeddingModel
     *
     * <p>对 embed(text) 调用做 Caffeine 缓存，同一文本 2 小时内不重复调用 API。
     * 通过 @Primary 优先注入，HybridRetrieverService 等自动获得缓存能力。
     */
    @Bean
    @Primary
    public EmbeddingModel cachedEmbeddingModel(
            EmbeddingModel delegate,
            @Qualifier("embeddingCacheManager") CacheManager embeddingCacheManager) {

        return new CachingEmbeddingModel(delegate, embeddingCacheManager);
    }

    /**
     * 缓存包装的 EmbeddingModel
     */
    static class CachingEmbeddingModel implements EmbeddingModel {

        private final EmbeddingModel delegate;
        private final Cache cache;

        CachingEmbeddingModel(EmbeddingModel delegate, CacheManager cacheManager) {
            this.delegate = delegate;
            this.cache = cacheManager.getCache("embeddings");
        }

        @Override
        public float[] embed(String text) {
            // 单文本缓存
            Float[] cached = cache.get(text, Float[].class);
            if (cached != null) {
                float[] result = new float[cached.length];
                for (int i = 0; i < cached.length; i++) {
                    result[i] = cached[i];
                }
                return result;
            }

            float[] vector = delegate.embed(text);
            // 缓存时转为 Float[]（Caffeine 不支持 primitive 数组）
            Float[] boxed = new Float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                boxed[i] = vector[i];
            }
            cache.put(text, boxed);
            return vector;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            // 批量请求直接透传，不做逐条缓存（批量场景通常是新数据导入）
            return delegate.call(request);
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }
    }
}
