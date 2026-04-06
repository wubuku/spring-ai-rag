package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FulltextSearchProviderFactory 全文检索策略工厂测试
 *
 * <p>测试策略：
 * <ul>
 *   <li>SearchCapabilities：用 init=false 构造，直接 set 字段</li>
 *   <li>Fake providers：简单不可变实现，isAvailable() 由构造参数决定</li>
 *   <li>Factory：用完全可控构造函数注入上述两者</li>
 * </ul>
 */
@DisplayName("FulltextSearchProviderFactory 全文检索策略工厂")
class FulltextSearchProviderFactoryTest {

    private JdbcTemplate jdbc;
    private RagProperties props;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        props = new RagProperties();
    }

    // ========== Fake Providers ==========

    private FulltextSearchProvider fakeJieba(boolean available) {
        return new FulltextSearchProvider() {
            @Override public String getName() { return "pg_jieba"; }
            @Override public boolean isAvailable() { return available; }
            @Override public List<RetrievalResult> search(String q, List<Long> docIds,
                    List<Long> excludeIds, int limit, double minScore) { return List.of(); }
        };
    }

    private FulltextSearchProvider fakeEnglish(boolean available) {
        return new FulltextSearchProvider() {
            @Override public String getName() { return "english_fts"; }
            @Override public boolean isAvailable() { return available; }
            @Override public List<RetrievalResult> search(String q, List<Long> docIds,
                    List<Long> excludeIds, int limit, double minScore) { return List.of(); }
        };
    }

    private FulltextSearchProvider fakeTrgm(boolean available) {
        return new FulltextSearchProvider() {
            @Override public String getName() { return "pg_trgm"; }
            @Override public boolean isAvailable() { return available; }
            @Override public List<RetrievalResult> search(String q, List<Long> docIds,
                    List<Long> excludeIds, int limit, double minScore) { return List.of(); }
        };
    }

    // ========== Helper: 构造 SearchCapabilities（init=false）==========

    private SearchCapabilities makeCaps(boolean hasJieba, boolean hasZhIndex,
                                        boolean hasEnIndex, boolean hasTrgm, boolean hasTrgmIndex) {
        SearchCapabilities caps = new SearchCapabilities(jdbc, false);
        caps.setHasJieba(hasJieba);
        caps.setHasZhIndex(hasZhIndex);
        caps.setHasEnIndex(hasEnIndex);
        caps.setHasPgTrgm(hasTrgm);
        caps.setHasTrgmIndex(hasTrgmIndex);
        caps.setHasPgVector(true);
        return caps;
    }

    // ========== Helper: 创建完全可控的工厂（inject fake providers）==========

    private FulltextSearchProviderFactory makeFactory(String strategy,
            SearchCapabilities caps, boolean jiebaAvail, boolean englishAvail, boolean trgmAvail) {
        FulltextSearchProvider fJieba = fakeJieba(jiebaAvail);
        FulltextSearchProvider fEnglish = fakeEnglish(englishAvail);
        FulltextSearchProvider fTrgm = fakeTrgm(trgmAvail);
        return new FulltextSearchProviderFactory(jdbc, strategy, caps, fJieba, fEnglish, fTrgm);
    }

    // ========== Helper: 创建工厂（使用真实 init=false capabilities）==========

    private FulltextSearchProviderFactory makeFactoryRealCaps(String strategy,
            boolean hasJieba, boolean hasZhIndex,
            boolean hasEnIndex, boolean hasTrgm, boolean hasTrgmIndex,
            boolean jiebaAvail, boolean englishAvail, boolean trgmAvail) {
        SearchCapabilities caps = makeCaps(hasJieba, hasZhIndex, hasEnIndex, hasTrgm, hasTrgmIndex);
        return makeFactory(strategy, caps, jiebaAvail, englishAvail, trgmAvail);
    }

    @Nested
    @DisplayName("auto 策略自动检测")
    class AutoStrategy {

        @Test
        @DisplayName("pg_jieba 可用时优先选择（ZH 查询）")
        void preferPgJiebaWhenAvailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", true, true, false, false, false, true, false, false);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.ZH);
            assertEquals("pg_jieba", provider.getName());
        }

        @Test
        @DisplayName("pg_jieba 不可用时降级到 pg_trgm（ZH 查询）")
        void fallbackToPgTrgmWhenJiebaUnavailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, false, true, true, false, false, true);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.ZH);
            assertEquals("pg_trgm", provider.getName());
        }

        @Test
        @DisplayName("都不可用时降级到 NoOp（ZH 查询）")
        void fallbackToNoOpWhenAllUnavailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, false, false, false, false, false, false);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.ZH);
            assertEquals("none", provider.getName());
        }

        @Test
        @DisplayName("英文查询优先选 English FTS")
        void preferEnglishFtsForEnQuery() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, true, true, true, false, true, true);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.EN_OR_OTHER);
            assertEquals("english_fts", provider.getName());
        }

        @Test
        @DisplayName("English FTS 不可用时降级到 pg_trgm（EN 查询）")
        void fallbackToTrgmWhenEnglishFtsUnavailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, true, true, true, false, false, true);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.EN_OR_OTHER);
            assertEquals("pg_trgm", provider.getName());
        }

        @Test
        @DisplayName("auto 降级链：jieba → trgm → none")
        void fullFallbackChain() {
            // jieba 不可用（hasZhIndex=false），trgm 可用 → trgm
            FulltextSearchProviderFactory factory1 = makeFactoryRealCaps(
                    "auto", true, false, false, true, true, false, false, true);
            assertEquals("pg_trgm", factory1.getProvider(QueryLang.ZH).getName());

            // 所有都不可用 → none
            FulltextSearchProviderFactory factory2 = makeFactoryRealCaps(
                    "auto", false, false, false, false, false, false, false, false);
            assertEquals("none", factory2.getProvider(QueryLang.ZH).getName());
        }
    }

    @Nested
    @DisplayName("显式策略选择")
    class ExplicitStrategy {

        @Test
        @DisplayName("none 策略返回 NoOp")
        void noneStrategyReturnsNoOp() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "none", false, false, false, false, false, false, false, false);
            assertEquals("none", factory.getProvider().getName());
        }

        @Test
        @DisplayName("pg_trgm 策略可用时返回 pg_trgm")
        void pgTrgmStrategyWhenAvailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "pg_trgm", false, false, false, true, true, false, false, true);
            assertEquals("pg_trgm", factory.getProvider().getName());
        }

        @Test
        @DisplayName("pg_trgm 策略不可用时抛异常")
        void pgTrgmStrategyThrowsWhenUnavailable() {
            SearchCapabilities caps = makeCaps(false, false, false, false, false);
            FulltextSearchProviderFactory factory = makeFactory("pg_trgm", caps, false, false, false);
            // IllegalStateException 在 getProvider() 时抛出，不在构造函数
            assertThrows(IllegalStateException.class, () -> factory.getProvider());
        }

        @Test
        @DisplayName("pg_jieba 策略可用时返回 pg_jieba")
        void pgJiebaStrategyWhenAvailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "pg_jieba", true, true, false, false, false, true, false, false);
            assertEquals("pg_jieba", factory.getProvider().getName());
        }

        @Test
        @DisplayName("pg_jieba 策略不可用时抛异常")
        void pgJiebaStrategyThrowsWhenUnavailable() {
            SearchCapabilities caps = makeCaps(false, false, false, false, false);
            FulltextSearchProviderFactory factory = makeFactory("pg_jieba", caps, false, false, false);
            // IllegalStateException 在 getProvider() 时抛出，不在构造函数
            assertThrows(IllegalStateException.class, () -> factory.getProvider());
        }
    }

    @Test
    @DisplayName("getStrategyLabel 返回策略显示名称")
    void getStrategyLabel() {
        assertEquals("auto-detect", FulltextSearchProviderFactory.getStrategyLabel("auto"));
        assertEquals("pg_jieba (Chinese segmentation)", FulltextSearchProviderFactory.getStrategyLabel("pg_jieba"));
        assertEquals("pg_trgm (trigram matching)", FulltextSearchProviderFactory.getStrategyLabel("pg_trgm"));
        assertEquals("disabled (vector-only)", FulltextSearchProviderFactory.getStrategyLabel("none"));
        assertEquals("unknown", FulltextSearchProviderFactory.getStrategyLabel("unknown"));
    }
}

@DisplayName("NoOpFulltextSearchProvider 空全文检索策略")
class NoOpFulltextSearchProviderTest {

    private NoOpFulltextSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NoOpFulltextSearchProvider();
    }

    @Test
    @DisplayName("名称为 none")
    void nameIsNone() {
        assertEquals("none", provider.getName());
    }

    @Test
    @DisplayName("始终可用")
    void alwaysAvailable() {
        assertTrue(provider.isAvailable());
    }

    @Test
    @DisplayName("搜索返回空列表")
    void searchReturnsEmpty() {
        List<RetrievalResult> results = provider.search("test query", null, null, 10, 0.3);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("带文档ID限制搜索返回空列表")
    void searchWithDocumentIdsReturnsEmpty() {
        List<RetrievalResult> results = provider.search("test",
                List.of(1L, 2L), List.of(3L), 5, 0.5);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
