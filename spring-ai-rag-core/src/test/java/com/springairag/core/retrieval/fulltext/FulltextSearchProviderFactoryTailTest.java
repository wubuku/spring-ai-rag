package com.springairag.core.retrieval.fulltext;

import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * FulltextSearchProviderFactory 长尾（Batch 521，JaCoCo 驱动）：
 * 无参禁用构造器、legacy 无语言参数的 getProvider() 自动探测链
 * （jieba → english → trgm → none）、未知固定策略回退按语言自动
 * 探测、语言检测边界。
 */
class FulltextSearchProviderFactoryTailTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private FulltextSearchProvider fakeProvider(String name, boolean available) {
        return new FulltextSearchProvider() {
            @Override public String getName() { return name; }
            @Override public boolean isAvailable() { return available; }
            @Override public List<com.springairag.api.dto.RetrievalResult> search(
                    String q, List<Long> docIds, List<Long> excludeIds,
                    int limit, double minScore, long embeddingProfileId) {
                return List.of();
            }
        };
    }

    private SearchCapabilities caps(boolean jieba, boolean zhIndex,
                                    boolean enIndex, boolean trgm,
                                    boolean trgmIndex) {
        SearchCapabilities capabilities = new SearchCapabilities(jdbc, false);
        capabilities.setHasJieba(jieba);
        capabilities.setHasZhIndex(zhIndex);
        capabilities.setHasEnIndex(enIndex);
        capabilities.setHasPgTrgm(trgm);
        capabilities.setHasTrgmIndex(trgmIndex);
        capabilities.setHasPgVector(true);
        return capabilities;
    }

    private FulltextSearchProviderFactory factory(
            String strategy, SearchCapabilities capabilities,
            boolean jiebaAvail, boolean englishAvail, boolean trgmAvail) {
        return new FulltextSearchProviderFactory(
                jdbc, strategy, capabilities,
                fakeProvider("pg_jieba", jiebaAvail),
                fakeProvider("english_fts", englishAvail),
                fakeProvider("pg_trgm", trgmAvail));
    }

    @Test
    void noArgConstructorCreatesFullyDisabledFactory() {
        FulltextSearchProviderFactory factory =
                new FulltextSearchProviderFactory();

        assertNull(factory.getCapabilities());
        assertEquals("none",
                factory.getProvider(QueryLang.ZH).getName());
        assertEquals("none",
                factory.getProvider().getName());
    }

    @Test
    void legacyGetProviderPrefersJiebaThenEnglishThenTrgm() {
        assertEquals("pg_jieba", factory("auto",
                caps(true, true, false, false, false),
                true, true, true).getProvider().getName());

        assertEquals("english_fts", factory("auto",
                caps(false, false, true, false, false),
                false, true, true).getProvider().getName());

        assertEquals("pg_trgm", factory("auto",
                caps(false, false, false, true, true),
                false, false, true).getProvider().getName());

        assertEquals("none", factory("auto",
                caps(false, false, false, false, false),
                false, false, false).getProvider().getName());
    }

    @Test
    void unknownFixedStrategyFallsBackToPerLanguageAutoDetect() {
        FulltextSearchProviderFactory factory =
                factory("bogus-strategy",
                        caps(false, false, false, true, true),
                        false, false, true);

        assertEquals("pg_trgm",
                factory.getProvider(QueryLang.ZH).getName());
        // 固定策略路径对英文同样走自动回退链。
        assertEquals("pg_trgm",
                factory.getProvider(QueryLang.EN_OR_OTHER).getName());
    }

    @Test
    void fixedTrgmStrategyThrowsWhenExtensionUnavailable() {
        FulltextSearchProviderFactory factory =
                factory("pg_trgm",
                        caps(false, false, false, true, true),
                        false, false, false);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> factory.getProvider(QueryLang.ZH));
        assertTrue(error.getMessage().contains("pg_trgm extension"));
    }

    @Test
    void detectLanguageClassifiesCjkAsciiAndBlank() {
        FulltextSearchProviderFactory factory =
                new FulltextSearchProviderFactory();

        assertEquals(QueryLang.ZH, factory.detectLang("中文检索"));
        assertEquals(QueryLang.EN_OR_OTHER,
                factory.detectLang("plain query"));
        assertEquals(QueryLang.EN_OR_OTHER, factory.detectLang(null));
        assertEquals(QueryLang.EN_OR_OTHER, factory.detectLang("   "));
    }
}
