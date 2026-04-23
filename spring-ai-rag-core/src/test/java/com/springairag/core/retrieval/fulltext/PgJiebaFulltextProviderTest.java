package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PgJiebaFulltextProvider Unit Tests
 */
class PgJiebaFulltextProviderTest {

    @Test
    @DisplayName("isAvailable=true when pg_jieba extension and jiebacfg config exist")
    void available_whenExtensionAndConfigExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // detectAvailability(): pg_jieba ext (Integer) + jiebacfg config (Integer) + search_vector_zh index (Boolean)
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("search_vector_zh"), eq(Boolean.class))).thenReturn(true);

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        assertTrue(provider.isAvailable());
        assertEquals("pg_jieba", provider.getName());
    }

    @Test
    @DisplayName("isAvailable=false when pg_jieba extension is missing")
    void unavailable_whenExtensionMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("isAvailable=false when jiebacfg config is missing")
    void unavailable_whenConfigMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // pg_jieba extension exists, but jiebacfg config query fails
        when(jdbc.queryForObject(contains("pg_jieba"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("jiebacfg"), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("search returns empty list when provider is unavailable")
    void search_whenUnavailable_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        assertTrue(provider.search("test", null, null, 5, 0.3).isEmpty());
    }

    @Test
    @DisplayName("search uses ts_rank and websearch_to_tsquery")
    void search_usesTsQueryAndRank() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // detectAvailability(): ext (Integer) + config (Integer) + index (Boolean)
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("search_vector_zh"), eq(Boolean.class))).thenReturn(true);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L); row.put("chunk_text", "测试文档"); row.put("document_id", 1L);
        row.put("chunk_index", 0); row.put("metadata", null); row.put("rank", 0.75);

        when(jdbc.queryForList(contains("ts_rank"), any(Object[].class))).thenReturn(List.of(row));

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        List<RetrievalResult> results = provider.search("测试", null, null, 5, 0.0);

        assertEquals(1, results.size());
        assertEquals(0.75, results.get(0).getFulltextScore(), 0.001);
        verify(jdbc).queryForList(contains("ts_rank"), any(Object[].class));
    }

    @Test
    @DisplayName("empty query returns empty list")
    void search_emptyQuery_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        assertTrue(provider.search(null, null, null, 5, 0.3).isEmpty());
        assertTrue(provider.search("", null, null, 5, 0.3).isEmpty());
    }

    @Test
    @DisplayName("search returns empty list on DB error without throwing")
    void search_dbError_returnsEmptyGracefully() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("DB error"));

        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);
        assertDoesNotThrow(() -> {
            assertTrue(provider.search("测试", null, null, 5, 0.3).isEmpty());
        });
    }
}
