package com.springairag.core.retrieval.fulltext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SearchCapabilities 单元测试
 *
 * <p>覆盖：
 * <ul>
 *   <li>No-arg 构造：所有能力 disabled</li>
 *   <li>Init=false 构造：字段由测试直接控制</li>
 *   <li>Init=true 构造：调用数据库探测</li>
 *   <li>@PostConstruct null guard：jdbcTemplate=null 时安全退出</li>
 *   <li>能力计算方法：enableChineseFts/enableEnglishFts/enableTrgm</li>
 *   <li>Getters</li>
 * </ul>
 */
@DisplayName("SearchCapabilities 数据库全文检索能力探测")
class SearchCapabilitiesTest {

    private JdbcTemplate jdbc;

    // ========== No-arg constructor tests ==========

    @Test
    @DisplayName("no-arg 构造：所有能力默认 false")
    void noArgConstructor_allCapabilitiesDisabled() {
        SearchCapabilities caps = new SearchCapabilities();

        assertFalse(caps.hasPgVector());
        assertFalse(caps.hasPgTrgm());
        assertFalse(caps.hasJieba());
        assertFalse(caps.hasZhparser());
        assertFalse(caps.hasZhIndex());
        assertFalse(caps.hasEnIndex());
        assertFalse(caps.hasTrgmIndex());
        assertFalse(caps.enableChineseFts());
        assertFalse(caps.enableEnglishFts());
        assertFalse(caps.enableTrgm());
    }

    // ========== Init=false constructor tests ==========

    @Test
    @DisplayName("init=false 构造：enableChineseFts 需要 jieba 扩展 + 索引同时存在")
    void enableChineseFts_requiresBothJiebaAndIndex() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        // 只有扩展，没有索引 → false
        caps.setHasJieba(true);
        caps.setHasZhIndex(false);
        assertFalse(caps.enableChineseFts());

        // 有索引，没有扩展 → false
        caps.setHasJieba(false);
        caps.setHasZhIndex(true);
        assertFalse(caps.enableChineseFts());

        // 两者都有 → true
        caps.setHasJieba(true);
        caps.setHasZhIndex(true);
        assertTrue(caps.enableChineseFts());
    }

    @Test
    @DisplayName("enableEnglishFts 只依赖 english 索引")
    void enableEnglishFts_requiresOnlyIndex() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        assertFalse(caps.enableEnglishFts()); // 无索引
        caps.setHasEnIndex(true);
        assertTrue(caps.enableEnglishFts());  // 有索引
    }

    @Test
    @DisplayName("enableTrgm 需要 pg_trgm 扩展 + 索引同时存在")
    void enableTrgm_requiresBothExtensionAndIndex() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        // 只有扩展，没有索引 → false
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(false);
        assertFalse(caps.enableTrgm());

        // 有索引，没有扩展 → false
        caps.setHasPgTrgm(false);
        caps.setHasTrgmIndex(true);
        assertFalse(caps.enableTrgm());

        // 两者都有 → true
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(true);
        assertTrue(caps.enableTrgm());
    }

    @Test
    @DisplayName("init=false 不调用数据库")
    void initFalse_doesNotCallDatabase() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        SearchCapabilities caps = new SearchCapabilities(mockJdbc, false);
        verifyNoInteractions(mockJdbc);
    }

    // ========== Init=true constructor tests ==========

    @Nested
    @DisplayName("init=true 构造：数据库探测")
    class InitTrueTests {

        @Test
        @DisplayName("正常检测扩展和索引")
        void detectExtensionsAndIndexes() {
            jdbc = mock(JdbcTemplate.class);
            // 模拟扩展检测返回：只有 vector + pg_trgm
            when(jdbc.queryForList(
                    contains("pg_extension"), eq(String.class)))
                    .thenReturn(List.of("vector", "pg_trgm"));

            // 模拟索引检测：只有 trgm 索引
            when(jdbc.queryForObject(contains("search_vector_zh"), eq(Boolean.class)))
                    .thenReturn(false);
            when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class)))
                    .thenReturn(false);
            when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                    .thenReturn(true);

            SearchCapabilities caps = new SearchCapabilities(jdbc, true);

            assertTrue(caps.hasPgVector());
            assertTrue(caps.hasPgTrgm());
            assertFalse(caps.hasJieba());
            assertFalse(caps.hasZhparser());
            assertFalse(caps.hasZhIndex());
            assertFalse(caps.hasEnIndex());
            assertTrue(caps.hasTrgmIndex());
        }

        @Test
        @DisplayName("检测失败时所有能力置为 false")
        void detectFailure_allCapabilitiesDisabled() {
            jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenThrow(new RuntimeException("DB error"));

            SearchCapabilities caps = new SearchCapabilities(jdbc, true);

            assertFalse(caps.hasPgVector());
            assertFalse(caps.hasPgTrgm());
            assertFalse(caps.hasJieba());
            assertFalse(caps.hasZhIndex());
            assertFalse(caps.hasEnIndex());
            assertFalse(caps.hasTrgmIndex());
        }

        @Test
        @DisplayName("扩展检测异常时能力置为 false（不抛异常）")
        void extensionDetectionException_caughtGracefully() {
            jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenThrow(new RuntimeException("connection failed"));

            assertDoesNotThrow(() -> {
                SearchCapabilities caps = new SearchCapabilities(jdbc, true);
                assertFalse(caps.hasPgVector());
            });
        }
    }

    // ========== @PostConstruct null guard tests ==========

    @Nested
    @DisplayName("@PostConstruct init null guard")
    class PostConstructNullGuard {

        @Test
        @DisplayName("jdbcTemplate=null 时 init 不抛异常，所有能力 false")
        void nullJdbcTemplate_doesNotThrow() {
            // 使用 no-arg 构造（jdbcTemplate=null）然后调用 init
            SearchCapabilities caps = new SearchCapabilities();
            assertDoesNotThrow(caps::init);
            assertFalse(caps.hasPgVector());
            assertFalse(caps.enableChineseFts());
        }

        @Test
        @DisplayName("正常 jdbcTemplate 时 init 正确检测")
        void normalJdbcTemplate_initsCorrectly() {
            jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(contains("pg_extension"), eq(String.class)))
                    .thenReturn(List.of("vector", "pg_jieba"));
            when(jdbc.queryForObject(contains("search_vector_zh"), eq(Boolean.class)))
                    .thenReturn(true);
            when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class)))
                    .thenReturn(false);
            when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                    .thenReturn(false);

            SearchCapabilities caps = new SearchCapabilities(jdbc);
            // @PostConstruct init() 被构造函数调用（init=true 路径）
            // 但 no-arg 调用 init=false，所以用显式调用测试
            SearchCapabilities caps2 = new SearchCapabilities(jdbc, false);
            caps2.init();

            assertTrue(caps2.hasPgVector());
            assertTrue(caps2.hasJieba());
            assertTrue(caps2.hasZhIndex());
            assertTrue(caps2.enableChineseFts());
        }
    }

    // ========== Getters and setters ==========

    @Test
    @DisplayName("所有 getter 返回字段值")
    void getters_returnFieldValues() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);
        caps.setHasPgVector(true);
        caps.setHasPgTrgm(true);
        caps.setHasJieba(false);
        caps.setHasZhparser(false);
        caps.setHasZhIndex(true);
        caps.setHasEnIndex(false);
        caps.setHasTrgmIndex(true);

        assertTrue(caps.hasPgVector());
        assertTrue(caps.hasPgTrgm());
        assertFalse(caps.hasJieba());
        assertFalse(caps.hasZhparser());
        assertTrue(caps.hasZhIndex());
        assertFalse(caps.hasEnIndex());
        assertTrue(caps.hasTrgmIndex());
    }

    @Test
    @DisplayName("所有 setter 正确设置字段")
    void setters_setFieldsCorrectly() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        caps.setHasPgVector(true);
        assertTrue(caps.hasPgVector());

        caps.setHasPgTrgm(true);
        assertTrue(caps.hasPgTrgm());

        caps.setHasJieba(true);
        assertTrue(caps.hasJieba());

        caps.setHasZhparser(true);
        assertTrue(caps.hasZhparser());

        caps.setHasZhIndex(true);
        assertTrue(caps.hasZhIndex());

        caps.setHasEnIndex(true);
        assertTrue(caps.hasEnIndex());

        caps.setHasTrgmIndex(true);
        assertTrue(caps.hasTrgmIndex());
    }

    // ========== Edge cases ==========

    @Test
    @DisplayName("ZH+EN+TRGM 全部启用时的能力计算")
    void allCapabilitiesEnabled_fulltextEnabled() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);
        caps.setHasPgVector(true);
        caps.setHasPgTrgm(true);
        caps.setHasJieba(true);
        caps.setHasZhparser(false);
        caps.setHasZhIndex(true);
        caps.setHasEnIndex(true);
        caps.setHasTrgmIndex(true);

        assertTrue(caps.enableChineseFts());  // jieba + zh_index
        assertTrue(caps.enableEnglishFts()); // en_index
        assertTrue(caps.enableTrgm());       // trgm + trgm_index
    }

    @Test
    @DisplayName("ZH 扩展缺失时 EN FTS 不受影响")
    void jiebaMissing_doesNotAffectEnglishFts() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);
        caps.setHasJieba(false);
        caps.setHasZhIndex(false);
        caps.setHasEnIndex(true);

        assertFalse(caps.enableChineseFts());
        assertTrue(caps.enableEnglishFts());
    }

    @Test
    @DisplayName("TRGM 扩展存在但索引缺失时不启用")
    void trgmExtensionWithoutIndex_notEnabled() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(false);

        assertFalse(caps.enableTrgm());
    }
}
