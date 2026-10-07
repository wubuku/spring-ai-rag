package com.springairag.core.config;

import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheConfig Unit Test
 *
 * <p>Batch 956：原来三条用例的断言清一色 {@code assertNotNull(...)}，
 * 可 {@code cacheManager()} 走的是"有配置就 new 一个出来"的路子，
 * 这条断言在数学上恒成立；{@code customConfigTakesEffect} 更是只断非空，
 * 名字里的 "TakesEffect" 一个字都没验——把 maximumSize 写成 1 还是 500，
 * 两条 assertNotNull 都不会红。
 *
 * <p>现在把配置<strong>读回来</strong>。绕的这条路径有点绕：{@code @Bean}
 * 方法只返回 {@code CacheManager}，{@code CaffeineCacheManager} 又没有
 * {@code getCaffeine()} 取规格；所以开一个具名缓存，再经
 * {@code CaffeineCache#getNativeCache()} 拿到底层 {@code Cache}，
 * 从它的 {@code policy()} 读回容量上限与 recordStats 开关。
 */
class CacheConfigTest {

    private final CacheConfig config = new CacheConfig(new RagProperties());

    /** 开一个具名缓存，拿到底层 Caffeine 缓存的运行时策略。 */
    private Policy<Object, Object> policyOf(CacheManager manager) {
        assertInstanceOf(CaffeineCacheManager.class, manager);
        org.springframework.cache.Cache springCache = manager.getCache("probe");
        assertNotNull(springCache, "getCache(\"probe\") 不该返回 null");
        assertInstanceOf(CaffeineCache.class, springCache);
        return ((CaffeineCache) springCache).getNativeCache().policy();
    }

    @Test
    @DisplayName("Default cache manager carries the default spec")
    void cacheManagerCreated() {
        RagProperties props = new RagProperties();
        Policy<Object, Object> policy =
                policyOf(new CacheConfig(props).cacheManager());

        assertEquals(props.getCache().getMaximumSize(),
                policy.eviction().orElseThrow().getMaximum(),
                "默认缓存的容量上限必须来自 rag.cache.maximum-size");
        assertTrue(policy.isRecordingStats(),
                "必须开启 recordStats，否则命中率之类的指标全是空的");
    }

    @Test
    @DisplayName("Embedding cache manager uses its own larger spec")
    void embeddingCacheManagerCreated() {
        RagProperties props = new RagProperties();
        Policy<Object, Object> policy =
                policyOf(new CacheConfig(props).embeddingCacheManager());

        // 向量缓存用自己的一套上限/TTL，跟普通缓存不是同一个数——
        // 这正是它单独成一个 @Bean 的理由，原来的 assertNotNull 看不出来。
        assertNotEquals(props.getCache().getMaximumSize(),
                props.getCache().getEmbeddingMaximumSize(),
                "本测试依赖两个默认值不同；若哪天改成同一个数，请一并改断言");
        assertEquals(props.getCache().getEmbeddingMaximumSize(),
                policy.eviction().orElseThrow().getMaximum());
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

        // 名字叫 TakesEffect，那就得真的生效：把配置值读回来对比。
        // 断非空的话，把 maximumSize 硬编码回默认值这条用例照样绿。
        assertEquals(500L,
                policyOf(customConfig.cacheManager()).eviction()
                        .orElseThrow().getMaximum());
        assertEquals(5000L,
                policyOf(customConfig.embeddingCacheManager()).eviction()
                        .orElseThrow().getMaximum());
    }
}
