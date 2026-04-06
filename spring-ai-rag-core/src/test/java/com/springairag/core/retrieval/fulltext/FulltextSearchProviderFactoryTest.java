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

    /** 模拟 pg_jieba 扩展可用且索引存在 */
    private void mockJiebaAvailable() {
        // SearchCapabilities: 扩展检测
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("vector", "pg_jieba"));
        // SearchCapabilities: 索引检测 (Boolean) — 所有查询都返回 true，SearchCapabilities 消耗后 PgJiebaFulltextProvider 的 index 检查继续使用同一 mock
        when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                .thenReturn(true);
        // PgJiebaFulltextProvider: 扩展和配置检测（使用 pg_extension 避免冲突）
        when(jdbc.queryForObject(contains("pg_extension"), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("pg_ts_config"), eq(Integer.class)))
                .thenReturn(1);
    }

    /** 模拟 pg_trgm 扩展可用且索引存在 */
    private void mockTrgmAvailable() {
        // SearchCapabilities: 扩展检测
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("vector", "pg_trgm"));
        // SearchCapabilities: 索引检测（所有索引都存在）
        when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                .thenReturn(true);
        // PgTrgmFulltextProvider: 使用更具体的匹配器，避免与 SearchCapabilities 冲突
        // detectAvailability() 查询 pg_extension（扩展）+ gin_trgm_ops（索引）
        when(jdbc.queryForObject(contains("pg_extension"), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
    }

    /** 模拟所有扩展不可用 */
    private void mockAllUnavailable() {
        // SearchCapabilities: 扩展检测返回空
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("vector"));
        // SearchCapabilities: 索引检测返回 false（所有 FTS 索引都不可用）
        when(jdbc.queryForObject(contains("search_vector_zh"), eq(Boolean.class)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("gin_trgm"), eq(Boolean.class)))
                .thenReturn(false);
        // detectAvailability() 的扩展检查也抛出异常（使用 pg_extension 避免与 SearchCapabilities 的 contains("pg_trgm") 冲突）
        doThrow(new EmptyResultDataAccessException(1) {})
                .when(jdbc).queryForObject(contains("pg_extension"), eq(Integer.class));
    }

    /** 模拟 pg_jieba 不可用但 pg_trgm 可用 */
    private void mockJiebaUnavailableTrgmAvailable() {
        // SearchCapabilities: 只有 pg_trgm 可用
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("vector", "pg_trgm"));
        // SearchCapabilities: 索引检测 - jieba 和 english 都不可用，只有 trgm 可用
        when(jdbc.queryForObject(contains("search_vector_zh"), eq(Boolean.class)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("gin_trgm"), eq(Boolean.class)))
                .thenReturn(true);
        // PgJiebaFulltextProvider: pg_jieba 不可用
        when(jdbc.queryForObject(contains("pg_jieba"), eq(Integer.class)))
                .thenThrow(EmptyResultDataAccessException.class);
        // PgTrgmFulltextProvider: pg_trgm 可用（使用 pg_extension 避免与 SearchCapabilities 的 index 查询冲突）
        when(jdbc.queryForObject(contains("pg_extension"), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
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
            mockJiebaUnavailableTrgmAvailable();

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
