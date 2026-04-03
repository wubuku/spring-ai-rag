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
 * PgTrgmFulltextProvider 单元测试
 */
class PgTrgmFulltextProviderTest {

    @Test
    @DisplayName("pg_trgm 可用时 isAvailable=true")
    void available_whenExtensionExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertTrue(provider.isAvailable());
        assertEquals("pg_trgm", provider.getName());
    }

    @Test
    @DisplayName("pg_trgm 不可用时 isAvailable=false")
    void unavailable_whenExtensionMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("不可用时 search 返回空列表")
    void search_whenUnavailable_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        List<RetrievalResult> results = provider.search("test", null, null, 5, 0.3);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("多词查询：每个关键词分别检索取最高相似度")
    void search_multiWord_takesBestScore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        Map<String, Object> row1 = new HashMap<>();
        row1.put("id", 1L); row1.put("chunk_text", "doc1"); row1.put("document_id", 1L);
        row1.put("chunk_index", 0); row1.put("metadata", null); row1.put("sim", 0.9);
        Map<String, Object> row2 = new HashMap<>();
        row2.put("id", 1L); row2.put("chunk_text", "doc1"); row2.put("document_id", 1L);
        row2.put("chunk_index", 0); row2.put("metadata", null); row2.put("sim", 0.5);

        // 第一个词返回高分，第二个词返回低分
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row1))
                .thenReturn(List.of(row2));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        List<RetrievalResult> results = provider.search("word1 word2", null, null, 5, 0.3);

        assertEquals(1, results.size());
        assertEquals(0.9, results.get(0).getScore(), 0.001); // 取最高分
    }

    @Test
    @DisplayName("结果低于 minScore 时被过滤")
    void search_belowMinScore_filtered() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L); row.put("chunk_text", "doc1"); row.put("document_id", 1L);
        row.put("chunk_index", 0); row.put("metadata", null); row.put("sim", 0.1);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        List<RetrievalResult> results = provider.search("test", null, null, 5, 0.3);

        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("excludeIds 被过滤")
    void search_excludeIds_filtered() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L); row.put("chunk_text", "doc1"); row.put("document_id", 1L);
        row.put("chunk_index", 0); row.put("metadata", null); row.put("sim", 0.8);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        List<RetrievalResult> results = provider.search("test", null, List.of(1L), 5, 0.3);
        assertTrue(results.isEmpty());

        List<RetrievalResult> results2 = provider.search("test", null, List.of(99L), 5, 0.3);
        assertEquals(1, results2.size());
    }

    @Test
    @DisplayName("数据库异常时返回空列表不抛异常")
    void search_dbError_returnsEmptyGracefully() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("DB error"));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertDoesNotThrow(() -> {
            List<RetrievalResult> results = provider.search("test", null, null, 5, 0.3);
            assertTrue(results.isEmpty());
        });
    }

    @Test
    @DisplayName("空查询返回空列表")
    void search_emptyQuery_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertTrue(provider.search("", null, null, 5, 0.3).isEmpty());
        assertTrue(provider.search("   ", null, null, 5, 0.3).isEmpty());
    }

    @Test
    @DisplayName("限定 documentIds 时 SQL 包含 IN 子句")
    void search_withDocumentIds_filtersByDocument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        provider.search("test", List.of(1L, 2L), null, 5, 0.3);

        verify(jdbc).queryForList(contains("document_id IN"), any(Object[].class));
    }
}
