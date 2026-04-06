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
 * PgEnglishFtsProvider unit tests
 */
class PgEnglishFtsProviderTest {

    @Test
    @DisplayName("index exists -> isAvailable=true")
    void available_whenIndexExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        assertTrue(provider.isAvailable());
        assertEquals("english_fts", provider.getName());
    }

    @Test
    @DisplayName("index missing -> isAvailable=false")
    void unavailable_whenIndexMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("index check throws -> isAvailable=false")
    void unavailable_whenCheckThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                .thenThrow(new RuntimeException("DB error"));

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("unavailable -> search returns empty list")
    void search_whenUnavailable_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        assertTrue(provider.search("test", null, null, 5, 0.3).isEmpty());
    }

    @Test
    @DisplayName("search uses ts_rank_cd and websearch_to_tsquery")
    void search_usesTsRankAndWebsearch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("chunk_text", "test document content");
        row.put("document_id", 42L);
        row.put("chunk_index", 3);
        row.put("metadata", null);
        row.put("rank", 0.65);

        when(jdbc.queryForList(contains("ts_rank_cd"), any(Object[].class))).thenReturn(List.of(row));

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        List<RetrievalResult> results = provider.search("test query", null, null, 5, 0.0);

        assertEquals(1, results.size());
        assertEquals(0.65, results.get(0).getFulltextScore(), 0.001);
        assertEquals("42", results.get(0).getDocumentId());
        assertEquals("test document content", results.get(0).getChunkText());
        assertEquals(3, results.get(0).getChunkIndex());
        verify(jdbc).queryForList(contains("ts_rank_cd"), any(Object[].class));
    }

    @Test
    @DisplayName("search filters by documentIds when provided")
    void search_withDocumentIds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 5L);
        row.put("chunk_text", "result text");
        row.put("document_id", 10L);
        row.put("chunk_index", 1);
        row.put("metadata", Map.of("source", "pdf"));
        row.put("rank", 0.8);

        when(jdbc.queryForList(contains("document_id IN"), any(Object[].class))).thenReturn(List.of(row));

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        List<RetrievalResult> results = provider.search("machine learning",
                List.of(10L, 20L, 30L), null, 10, 0.0);

        assertEquals(1, results.size());
        assertEquals("10", results.get(0).getDocumentId());
        assertEquals(Map.of("source", "pdf"), results.get(0).getMetadata());
    }

    @Test
    @DisplayName("search filters by minScore")
    void search_respectsMinScore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("chunk_text", "doc");
        row.put("document_id", 1L);
        row.put("chunk_index", 0);
        row.put("metadata", null);
        row.put("rank", 0.4); // below 0.5 threshold

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        List<RetrievalResult> results = provider.search("query", null, null, 5, 0.5);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search filters by excludeIds")
    void search_respectsExcludeIds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 99L); // should be excluded
        row.put("chunk_text", "excluded doc");
        row.put("document_id", 1L);
        row.put("chunk_index", 0);
        row.put("metadata", null);
        row.put("rank", 0.9);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        List<RetrievalResult> results = provider.search("query", null, List.of(99L), 5, 0.0);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("empty or blank query returns empty list")
    void search_emptyQuery_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Constructor calls detectAvailability which queries the index
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        assertTrue(provider.search(null, null, null, 5, 0.3).isEmpty());
        assertTrue(provider.search("", null, null, 5, 0.3).isEmpty());
        assertTrue(provider.search("   ", null, null, 5, 0.3).isEmpty());
        // Only the constructor's detectAvailability call should have occurred; no search queries
        verify(jdbc, atLeastOnce()).queryForObject(contains("search_vector_en"), eq(Boolean.class));
        // No queryForList calls for search
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("DB error returns empty gracefully")
    void search_dbError_returnsEmptyGracefully() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("search_vector_en"), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        PgEnglishFtsProvider provider = new PgEnglishFtsProvider(jdbc);
        assertDoesNotThrow(() ->
                assertTrue(provider.search("query", null, null, 5, 0.3).isEmpty()));
    }
}
