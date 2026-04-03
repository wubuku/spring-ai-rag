package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("FulltextSearchProviderFactory 全文检索策略工厂")
class FulltextSearchProviderFactoryTest {

    private JdbcTemplate jdbc;
    private RagProperties props;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        props = new RagProperties();
    }

    /** 模拟 pg_jieba 扩展可用 */
    private void mockJiebaAvailable() {
        // PgJiebaFulltextProvider 检查两个 SQL
        when(jdbc.queryForObject("SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba'", Integer.class))
                .thenReturn(1);
        when(jdbc.queryForObject("SELECT 1 FROM pg_ts_config WHERE cfgname = 'jiebacfg'", Integer.class))
                .thenReturn(1);
    }

    /** 模拟 pg_trgm 扩展可用 */
    private void mockTrgmAvailable() {
        when(jdbc.queryForObject("SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
                .thenReturn(1);
    }

    /** 模拟所有扩展不可用 */
    private void mockAllUnavailable() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(EmptyResultDataAccessException.class);
    }

    @Nested
    @DisplayName("auto 策略自动检测")
    class AutoStrategy {

        @Test
        @DisplayName("pg_jieba 可用时优先选择")
        void preferPgJiebaWhenAvailable() {
            props.getRetrieval().setFulltextStrategy("auto");
            mockJiebaAvailable();

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            FulltextSearchProvider provider = factory.getProvider();

            assertNotNull(provider);
            assertEquals("pg_jieba", provider.getName());
        }

        @Test
        @DisplayName("pg_jieba 不可用时降级到 pg_trgm")
        void fallbackToPgTrgm() {
            props.getRetrieval().setFulltextStrategy("auto");
            mockTrgmAvailable();
            // pg_jieba 不可用：pg_extension 查询抛异常
            when(jdbc.queryForObject("SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba'", Integer.class))
                    .thenThrow(EmptyResultDataAccessException.class);

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            FulltextSearchProvider provider = factory.getProvider();

            assertNotNull(provider);
            assertEquals("pg_trgm", provider.getName());
        }

        @Test
        @DisplayName("都不可用时降级到 NoOp")
        void fallbackToNoOp() {
            props.getRetrieval().setFulltextStrategy("auto");
            mockAllUnavailable();

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            FulltextSearchProvider provider = factory.getProvider();

            assertNotNull(provider);
            assertEquals("none", provider.getName());
        }
    }

    @Nested
    @DisplayName("显式策略选择")
    class ExplicitStrategy {

        @Test
        @DisplayName("none 策略返回 NoOp")
        void noneStrategyReturnsNoOp() {
            props.getRetrieval().setFulltextStrategy("none");

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            FulltextSearchProvider provider = factory.getProvider();

            assertNotNull(provider);
            assertEquals("none", provider.getName());
        }

        @Test
        @DisplayName("pg_trgm 策略可用时返回 pg_trgm")
        void pgTrgmStrategyWhenAvailable() {
            props.getRetrieval().setFulltextStrategy("pg_trgm");
            mockTrgmAvailable();

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            FulltextSearchProvider provider = factory.getProvider();

            assertNotNull(provider);
            assertEquals("pg_trgm", provider.getName());
        }

        @Test
        @DisplayName("pg_trgm 策略不可用时抛异常")
        void pgTrgmStrategyThrowsWhenUnavailable() {
            props.getRetrieval().setFulltextStrategy("pg_trgm");
            mockAllUnavailable();

            assertThrows(IllegalStateException.class,
                    () -> new FulltextSearchProviderFactory(jdbc, props));
        }

        @Test
        @DisplayName("pg_jieba 策略可用时返回 pg_jieba")
        void pgJiebaStrategyWhenAvailable() {
            props.getRetrieval().setFulltextStrategy("pg_jieba");
            mockJiebaAvailable();

            FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(jdbc, props);
            FulltextSearchProvider provider = factory.getProvider();

            assertNotNull(provider);
            assertEquals("pg_jieba", provider.getName());
        }

        @Test
        @DisplayName("pg_jieba 策略不可用时抛异常")
        void pgJiebaStrategyThrowsWhenUnavailable() {
            props.getRetrieval().setFulltextStrategy("pg_jieba");
            mockAllUnavailable();

            assertThrows(IllegalStateException.class,
                    () -> new FulltextSearchProviderFactory(jdbc, props));
        }
    }

    @Test
    @DisplayName("getStrategyLabel 返回策略显示名称")
    void getStrategyLabel() {
        assertEquals("auto-detect", FulltextSearchProviderFactory.getStrategyLabel("auto"));
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
