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
 * Unit Tests for FulltextSearchProviderFactory
 *
 * <p>Test strategy:
 * <ul>
 *   <li>SearchCapabilities: constructed with init=false, set fields directly</li>
 *   <li>Fake providers: simple immutable implementations, isAvailable() determined by constructor arg</li>
 *   <li>Factory: injects both with fully controllable constructor</li>
 * </ul>
 */
@DisplayName("FulltextSearchProviderFactory Unit Tests")
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

    // ========== Helper: construct SearchCapabilities (init=false) ==========

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

    // ========== Helper: create fully controllable factory (inject fake providers) ==========

    private FulltextSearchProviderFactory makeFactory(String strategy,
            SearchCapabilities caps, boolean jiebaAvail, boolean englishAvail, boolean trgmAvail) {
        FulltextSearchProvider fJieba = fakeJieba(jiebaAvail);
        FulltextSearchProvider fEnglish = fakeEnglish(englishAvail);
        FulltextSearchProvider fTrgm = fakeTrgm(trgmAvail);
        return new FulltextSearchProviderFactory(jdbc, strategy, caps, fJieba, fEnglish, fTrgm);
    }

    // ========== Helper: create factory (using real init=false capabilities) ==========

    private FulltextSearchProviderFactory makeFactoryRealCaps(String strategy,
            boolean hasJieba, boolean hasZhIndex,
            boolean hasEnIndex, boolean hasTrgm, boolean hasTrgmIndex,
            boolean jiebaAvail, boolean englishAvail, boolean trgmAvail) {
        SearchCapabilities caps = makeCaps(hasJieba, hasZhIndex, hasEnIndex, hasTrgm, hasTrgmIndex);
        return makeFactory(strategy, caps, jiebaAvail, englishAvail, trgmAvail);
    }

    @Nested
    @DisplayName("Auto Strategy Detection")
    class AutoStrategy {

        @Test
        @DisplayName("pg_jieba available: preferred for ZH query")
        void preferPgJiebaWhenAvailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", true, true, false, false, false, true, false, false);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.ZH);
            assertEquals("pg_jieba", provider.getName());
        }

        @Test
        @DisplayName("pg_jieba unavailable: falls back to pg_trgm for ZH query")
        void fallbackToPgTrgmWhenJiebaUnavailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, false, true, true, false, false, true);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.ZH);
            assertEquals("pg_trgm", provider.getName());
        }

        @Test
        @DisplayName("all unavailable: falls back to NoOp for ZH query")
        void fallbackToNoOpWhenAllUnavailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, false, false, false, false, false, false);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.ZH);
            assertEquals("none", provider.getName());
        }

        @Test
        @DisplayName("English query: prefers English FTS")
        void preferEnglishFtsForEnQuery() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, true, true, true, false, true, true);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.EN_OR_OTHER);
            assertEquals("english_fts", provider.getName());
        }

        @Test
        @DisplayName("English FTS unavailable: falls back to pg_trgm for EN query")
        void fallbackToTrgmWhenEnglishFtsUnavailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "auto", false, false, true, true, true, false, false, true);
            FulltextSearchProvider provider = factory.getProvider(QueryLang.EN_OR_OTHER);
            assertEquals("pg_trgm", provider.getName());
        }

        @Test
        @DisplayName("auto fallback chain: jieba -> trgm -> none")
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
    @DisplayName("Explicit strategy selection")
    class ExplicitStrategy {

        @Test
        @DisplayName("none strategy returns NoOp")
        void noneStrategyReturnsNoOp() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "none", false, false, false, false, false, false, false, false);
            assertEquals("none", factory.getProvider().getName());
        }

        @Test
        @DisplayName("pg_trgm strategy: returns pg_trgm when available")
        void pgTrgmStrategyWhenAvailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "pg_trgm", false, false, false, true, true, false, false, true);
            assertEquals("pg_trgm", factory.getProvider().getName());
        }

        @Test
        @DisplayName("pg_trgm strategy: throws when unavailable")
        void pgTrgmStrategyThrowsWhenUnavailable() {
            SearchCapabilities caps = makeCaps(false, false, false, false, false);
            FulltextSearchProviderFactory factory = makeFactory("pg_trgm", caps, false, false, false);
            // IllegalStateException 在 getProvider() 时抛出，不在构造函数
            assertThrows(IllegalStateException.class, () -> factory.getProvider());
        }

        @Test
        @DisplayName("pg_jieba strategy: returns pg_jieba when available")
        void pgJiebaStrategyWhenAvailable() {
            FulltextSearchProviderFactory factory = makeFactoryRealCaps(
                    "pg_jieba", true, true, false, false, false, true, false, false);
            assertEquals("pg_jieba", factory.getProvider().getName());
        }

        @Test
        @DisplayName("pg_jieba strategy: throws when unavailable")
        void pgJiebaStrategyThrowsWhenUnavailable() {
            SearchCapabilities caps = makeCaps(false, false, false, false, false);
            FulltextSearchProviderFactory factory = makeFactory("pg_jieba", caps, false, false, false);
            // IllegalStateException 在 getProvider() 时抛出，不在构造函数
            assertThrows(IllegalStateException.class, () -> factory.getProvider());
        }
    }

    @Test
    @DisplayName("getStrategyLabel returns strategy display name")
    void getStrategyLabel() {
        assertEquals("auto-detect", FulltextSearchProviderFactory.getStrategyLabel("auto"));
        assertEquals("pg_jieba (Chinese segmentation)", FulltextSearchProviderFactory.getStrategyLabel("pg_jieba"));
        assertEquals("pg_trgm (trigram matching)", FulltextSearchProviderFactory.getStrategyLabel("pg_trgm"));
        assertEquals("disabled (vector-only)", FulltextSearchProviderFactory.getStrategyLabel("none"));
        assertEquals("unknown", FulltextSearchProviderFactory.getStrategyLabel("unknown"));
    }
}

@DisplayName("NoOpFulltextSearchProvider Unit Tests")
class NoOpFulltextSearchProviderTest {

    private NoOpFulltextSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NoOpFulltextSearchProvider();
    }

    @Test
    @DisplayName("getName returns 'none'")
    void nameIsNone() {
        assertEquals("none", provider.getName());
    }

    @Test
    @DisplayName("isAvailable always returns true (safe fallback)")
    void alwaysAvailable() {
        assertTrue(provider.isAvailable(),
                "NoOp provider must always be available to serve as safe fallback");
    }

    @Test
    @DisplayName("search returns empty list for any query")
    void searchReturnsEmpty() {
        List<RetrievalResult> results = provider.search("test query", null, null, 10, 0.3);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search returns empty list with document ID and exclude ID filters")
    void searchWithDocumentIdsReturnsEmpty() {
        List<RetrievalResult> results = provider.search("test",
                List.of(1L, 2L), List.of(3L), 5, 0.5);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search returns empty list for null query")
    void searchNullQueryReturnsEmpty() {
        List<RetrievalResult> results = provider.search(null, null, null, 10, 0.0);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search returns empty list when limit is zero")
    void searchZeroLimitReturnsEmpty() {
        List<RetrievalResult> results = provider.search("anything", null, null, 0, 0.0);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search result is unmodifiable")
    void searchResultIsUnmodifiable() {
        List<RetrievalResult> results = provider.search("query", null, null, 10, 0.5);
        assertThrows(UnsupportedOperationException.class, () -> results.add(null));
    }

    @Test
    @DisplayName("implements FulltextSearchProvider interface")
    void implementsFulltextSearchProviderInterface() {
        assertInstanceOf(FulltextSearchProvider.class, provider);
    }
}
