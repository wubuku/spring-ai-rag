package com.springairag.core.retrieval.fulltext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for SearchCapabilities
 *
 * <p>Covers:
 * <ul>
 *   <li>No-arg constructor: all capabilities disabled</li>
 *   <li>Init=false constructor: fields controlled directly by tests</li>
 *   <li>Init=true constructor: calls database detection</li>
 *   <li>@PostConstruct null guard: safe exit when jdbcTemplate=null</li>
 *   <li>Capability calculation methods: enableChineseFts/enableEnglishFts/enableTrgm</li>
 *   <li>Getters and setters</li>
 * </ul>
 */
@DisplayName("SearchCapabilities - Database Full-Text Search Capability Detection")
class SearchCapabilitiesTest {

    private JdbcTemplate jdbc;

    // ========== No-arg constructor tests ==========

    @Test
    @DisplayName("no-arg constructor: all capabilities default to false")
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
    @DisplayName("init=false constructor: enableChineseFts requires both jieba extension and index")
    void enableChineseFts_requiresBothJiebaAndIndex() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        // Extension only, no index -> false
        caps.setHasJieba(true);
        caps.setHasZhIndex(false);
        assertFalse(caps.enableChineseFts());

        // Index only, no extension -> false
        caps.setHasJieba(false);
        caps.setHasZhIndex(true);
        assertFalse(caps.enableChineseFts());

        // Both present -> true
        caps.setHasJieba(true);
        caps.setHasZhIndex(true);
        assertTrue(caps.enableChineseFts());
    }

    @Test
    @DisplayName("enableEnglishFts requires only english index")
    void enableEnglishFts_requiresOnlyIndex() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        assertFalse(caps.enableEnglishFts()); // no index
        caps.setHasEnIndex(true);
        assertTrue(caps.enableEnglishFts());  // with index
    }

    @Test
    @DisplayName("enableTrgm requires both pg_trgm extension and index")
    void enableTrgm_requiresBothExtensionAndIndex() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);

        // Extension only, no index -> false
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(false);
        assertFalse(caps.enableTrgm());

        // Index only, no extension -> false
        caps.setHasPgTrgm(false);
        caps.setHasTrgmIndex(true);
        assertFalse(caps.enableTrgm());

        // Both present -> true
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(true);
        assertTrue(caps.enableTrgm());
    }

    @Test
    @DisplayName("init=false does not call database")
    void initFalse_doesNotCallDatabase() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        SearchCapabilities caps = new SearchCapabilities(mockJdbc, false);
        verifyNoInteractions(mockJdbc);
    }

    // ========== Init=true constructor tests ==========

    @Nested
    @DisplayName("init=true constructor: database detection")
    class InitTrueTests {

        @Test
        @DisplayName("Detects extensions and indexes normally")
        void detectExtensionsAndIndexes() {
            jdbc = mock(JdbcTemplate.class);
            // Mock extension detection returns: vector + pg_trgm only
            when(jdbc.queryForList(
                    contains("pg_extension"), eq(String.class)))
                    .thenReturn(List.of("vector", "pg_trgm"));

            // Mock index detection returns: trgm index only
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
        @DisplayName("detection failure: all capabilities set to false")
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
        @DisplayName("extension detection exception: capabilities set to false without throwing")
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
        @DisplayName("jdbcTemplate=null: init does not throw, all capabilities false")
        void nullJdbcTemplate_doesNotThrow() {
            // 使用 no-arg 构造（jdbcTemplate=null）然后调用 init
            SearchCapabilities caps = new SearchCapabilities();
            assertDoesNotThrow(caps::init);
            assertFalse(caps.hasPgVector());
            assertFalse(caps.enableChineseFts());
        }

        @Test
        @DisplayName("normal jdbcTemplate: init detects correctly")
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
            // @PostConstruct init() is called by constructor (init=true path)
            // but no-arg constructor calls init=false, so use explicit call for testing
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
    @DisplayName("all getters return field values")
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
    @DisplayName("all setters correctly set fields")
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
    @DisplayName("ZH+EN+TRGM all enabled: capability calculation")
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
    @DisplayName("ZH extension missing: EN FTS not affected")
    void jiebaMissing_doesNotAffectEnglishFts() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);
        caps.setHasJieba(false);
        caps.setHasZhIndex(false);
        caps.setHasEnIndex(true);

        assertFalse(caps.enableChineseFts());
        assertTrue(caps.enableEnglishFts());
    }

    @Test
    @DisplayName("TRGM extension present but index missing: not enabled")
    void trgmExtensionWithoutIndex_notEnabled() {
        SearchCapabilities caps = new SearchCapabilities(mock(JdbcTemplate.class), false);
        caps.setHasPgTrgm(true);
        caps.setHasTrgmIndex(false);

        assertFalse(caps.enableTrgm());
    }
}
