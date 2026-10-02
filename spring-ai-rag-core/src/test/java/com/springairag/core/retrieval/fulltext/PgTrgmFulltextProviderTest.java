package com.springairag.core.retrieval.fulltext;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PgTrgmFulltextProvider Unit Tests
 */
class PgTrgmFulltextProviderTest {

    /** Test subclass that overrides executeSearch for controlled testing */
    static class TestPgTrgmProviderWithFixedSearch extends PgTrgmFulltextProvider {
        private final java.util.List<java.util.Map<String, Object>> fixedResult;

        TestPgTrgmProviderWithFixedSearch(JdbcTemplate jdbc,
                java.util.List<java.util.Map<String, Object>> result) {
            super(jdbc);
            this.fixedResult = result != null ? result : java.util.Collections.emptyList();
        }

        @Override
        java.util.List<java.util.Map<String, Object>> executeSearch(String query,
                RetrievalScope scope, int limit, long embeddingProfileId) {
            // Returns fixed result for testing; filtering by minScore and excludeIds is tested separately
            return fixedResult;
        }
    }

    @Test
    @DisplayName("isAvailable=true when pg_trgm extension exists")
    void available_whenExtensionExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // detectAvailability(): extension check (Integer) + index existence check (Boolean)
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertTrue(provider.isAvailable());
        assertEquals("pg_trgm", provider.getName());
    }

    @Test
    @DisplayName("isAvailable=false when pg_trgm extension is missing")
    void unavailable_whenExtensionMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertFalse(provider.isAvailable());
    }

    @Test
    @DisplayName("search returns empty list when provider is unavailable")
    void search_whenUnavailable_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("not found"));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        List<RetrievalResult> results = provider.search("test", null, null, 5, 0.3, 1L);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("the whole trimmed query is one similarity argument, not per-keyword")
    void search_multiWord_passesWholeQueryToSimilarity() {
        // The previous version of this test asserted per-keyword search with the
        // best score kept. The implementation has never done that: `executeSearch`
        // binds the trimmed query three times for the two POSITION() probes and
        // one `similarity(e.chunk_text, ?)`, so a multi-word query is compared as a
        // single trigram. The test was disabled with a comment claiming the
        // behaviour lived elsewhere; it did not. What is actually worth pinning is
        // the argument binding, because a future "improve multi-word recall"
        // change would otherwise land without anyone noticing which of the two
        // behaviours it replaced.
        JdbcTemplate jdbc = availableJdbc();
        when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(List.of());

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        provider.search("  hello world  ", List.of(1L, 2L), null, 5, 0.3, 1L);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), args.capture());
        Object[] bound = args.getValue();
        assertTrue(Stream.of(bound).filter("hello world"::equals).count() >= 3,
                "the trimmed query should be bound for both POSITION probes and similarity(), got "
                        + Arrays.toString(bound));
    }

    @Test
    @DisplayName("results below minScore are filtered out")
    void search_belowMinScore_filtered() {
        PgTrgmFulltextProvider provider =
                new TestPgTrgmProviderWithFixedSearch(availableJdbc(),
                        List.of(row(1L, 0.05), row(2L, 0.40)));

        List<RetrievalResult> results = provider.search("test", SCOPE_IDS, null, 5, 0.30, 1L);

        assertEquals(1, results.size(), "only the row at or above minScore survives");
        assertEquals(0.40, results.get(0).getScore(), 1e-9);
    }

    @Test
    @DisplayName("a row exactly at minScore is kept (the comparison is >=, not >)")
    void search_atExactlyMinScore_kept() {
        PgTrgmFulltextProvider provider =
                new TestPgTrgmProviderWithFixedSearch(availableJdbc(),
                        List.of(row(1L, 0.30)));

        assertEquals(1, provider.search("test", SCOPE_IDS, null, 5, 0.30, 1L).size());
    }

    @Test
    @DisplayName("excludeIds are filtered from results")
    void search_excludeIds_filtered() {
        PgTrgmFulltextProvider provider =
                new TestPgTrgmProviderWithFixedSearch(availableJdbc(),
                        List.of(row(5L, 0.9), row(9L, 0.8)));

        List<RetrievalResult> results =
                provider.search("test", SCOPE_IDS, List.of(5L), 5, 0.3, 1L);

        assertEquals(1, results.size(), "the excluded embedding must not reach the candidate pool");
        assertEquals("9", results.get(0).getDocumentId());
    }

    /**
     * RetrievalScope: a null id list means "match none" and short-circuits before
     * the database is touched, which is why the pre-existing tests here all pass
     * null and never reach the row mapping.
     */
    private static final List<Long> SCOPE_IDS = List.of(1L, 2L, 5L, 9L);

    /** A row shaped like the one `executeSearchInternal` returns. */
    private static Map<String, Object> row(long embeddingId, double score) {
        Map<String, Object> row = new HashMap<>();
        row.put("local_chunk_id", embeddingId);
        row.put("embedding_id", embeddingId);
        row.put("document_id", embeddingId);
        row.put("chunk_text", "chunk " + embeddingId);
        row.put("chunk_index", 0);
        row.put("score_trgm", score);
        row.put("metadata", Map.of());
        return row;
    }

    /** A JdbcTemplate stubbed so `detectAvailability()` reports pg_trgm present. */
    private static JdbcTemplate availableJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        return jdbc;
    }

    @Test
    @DisplayName("search returns empty list on DB error without throwing")
    void search_dbError_returnsEmptyGracefully() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        when(jdbc.update(anyString(), (Object) any())).thenReturn(1);
        when(jdbc.queryForList(anyString(), (Object[]) any()))
                .thenThrow(new DataAccessResourceFailureException("DB error"));

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertDoesNotThrow(() -> {
            List<RetrievalResult> results = provider.search("test", null, null, 5, 0.3, 1L);
            assertTrue(results.isEmpty());
        });
    }

    @Test
    @DisplayName("empty query returns empty list")
    void search_emptyQuery_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        assertTrue(provider.search("", null, null, 5, 0.3, 1L).isEmpty());
        assertTrue(provider.search("   ", null, null, 5, 0.3, 1L).isEmpty());
    }

    @Test
    @DisplayName("search handles NULL score_trgm gracefully (defaults to 0.0)")
    void search_nullScore_returnsEmptyOrZeroScore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        // Row with NULL score_trgm (similarity() can return NULL for edge cases)
        Map<String, Object> nullScoreRow = new HashMap<>();
        nullScoreRow.put("id", 1);
        nullScoreRow.put("score_trgm", null);  // NULL score - edge case
        nullScoreRow.put("document_id", 10);
        nullScoreRow.put("chunk_text", "test content");
        nullScoreRow.put("chunk_index", 0);
        nullScoreRow.put("metadata", Collections.emptyMap());
        nullScoreRow.put("document_title", "PDF");
        nullScoreRow.put("document_source", "pdf-import:uuid-trgm/default.md");
        nullScoreRow.put("original_filename", "trgm.pdf");

        PgTrgmFulltextProvider provider =
                new TestPgTrgmProviderWithFixedSearch(jdbc, List.of(nullScoreRow));
        List<RetrievalResult> results = provider.search("test", null, null, 5, 0.0, 1L);
        assertEquals(1, results.size());
        assertEquals("uuid-trgm/default.md", results.get(0).getIndexedFilePath());
        assertEquals("trgm.pdf", results.get(0).getOriginalFilename());
    }

    @Test
    @DisplayName("detailed search reports candidates removed by minScore")
    void detailedSearch_reportsRawCandidatesBeforeMinScore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("score_trgm", 0.2);
        row.put("document_id", 10L);
        row.put("chunk_text", "low score");
        row.put("chunk_index", 0);
        row.put("metadata", Collections.emptyMap());
        row.put("document_title", "title");
        row.put("document_source", "source");

        PgTrgmFulltextProvider provider =
                new TestPgTrgmProviderWithFixedSearch(jdbc, List.of(row));

        FulltextSearchProvider.SearchResult result =
                provider.searchInScopeDetailed(
                        "query",
                        RetrievalScope.unscoped(),
                        null,
                        5,
                        0.8,
                        1L,
                        com.springairag.core.retrieval.RetrievalFilters.none());

        assertTrue(result.results().isEmpty());
        assertEquals(1, result.candidateCount());
    }

    @Test
    @DisplayName("SQL includes IN clause when documentIds are specified")
    void search_withDocumentIds_filtersByDocument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("gin_trgm_ops"), eq(Boolean.class))).thenReturn(true);
        when(jdbc.update(anyString(), (Object) any())).thenReturn(1);
        when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(Collections.emptyList());

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        provider.search("test", List.of(1L, 2L), null, 5, 0.3, 1L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("FROM rag_document_chunks e"));
        assertTrue(sql.contains("v.embedding_profile_id = 1"));
        assertTrue(sql.contains("s.local_index_status = 'READY'"));
        assertTrue(sql.contains("s.content_hash = d.content_hash"));
        assertTrue(sql.contains("d.enabled = true"));
        assertTrue(sql.contains("d.source AS document_source"));
        assertTrue(sql.contains("d.original_filename AS original_filename"));
        verify(jdbc).queryForObject(anyString(), eq(Integer.class));
    }

    @Test
    @DisplayName("scope predicates are pushed into pg_trgm SQL")
    void searchInScope_pushesCollectionDocumentAndTypePredicates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(
                contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.update(anyString(), (Object) any())).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        provider.searchInScope(
                "records",
                RetrievalScope.selectedCollections(
                        List.of(2L, 4L), List.of(10L), "json-record"),
                null, 5, 0.0, 7L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(
                sqlCaptor.capture(), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("d.collection_id = ANY (?)"));
        assertTrue(sql.contains("e.document_id = ANY (?)"));
        assertTrue(sql.contains("d.document_type = ?"));
        assertTrue(sql.contains("v.embedding_profile_id = 7"));
        Object[] args = argsCaptor.getValue();
        assertInstanceOf(
                org.springframework.jdbc.support.SqlArrayValue.class, args[3]);
        assertInstanceOf(
                org.springframework.jdbc.support.SqlArrayValue.class, args[4]);
        assertEquals("json-record", args[5]);
    }

    @Test
    @DisplayName("JSONB containment is bound before trigram LIMIT")
    void searchInScope_payloadFilterIsPushedIntoSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(
                contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.update(anyString(), (Object) any())).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        PgTrgmFulltextProvider provider =
                new PgTrgmFulltextProvider(jdbc);
        provider.searchInScope(
                "sofa",
                RetrievalScope.selectedCollections(
                        List.of(7L), null, "json-record"),
                null,
                5,
                0.1,
                1L,
                new JsonbContainmentFilter(
                        "{\"status\":\"active\"}"));

        ArgumentCaptor<String> sqlCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(
                sqlCaptor.capture(), argsCaptor.capture());
        String sql = sqlCaptor.getValue();
        int filterPosition = sql.indexOf(
                "d.jsonb_payload @> CAST(? AS jsonb)");
        assertTrue(filterPosition > 0);
        assertTrue(filterPosition < sql.indexOf("ORDER BY"));
        assertEquals(
                "{\"status\":\"active\"}",
                argsCaptor.getValue()[5]);
    }

    @Test
    @DisplayName("SQL preserves exact chunk or title matches above the fuzzy threshold")
    void search_exactMatchBypassesMinScore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(
                contains("gin_trgm_ops"), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        PgTrgmFulltextProvider provider = new PgTrgmFulltextProvider(jdbc);
        provider.searchInScope(
                "蓝色手机",
                RetrievalScope.unscoped(),
                null,
                5,
                0.5,
                1L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(
                sqlCaptor.capture(), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains(
                "POSITION(LOWER(?) IN LOWER(COALESCE(e.chunk_text, ''))) > 0"));
        assertTrue(sql.contains(
                "POSITION(LOWER(?) IN LOWER(COALESCE(d.title, ''))) > 0"));
        assertTrue(sql.contains("similarity(e.chunk_text, ?) >= ?"));
        assertEquals("蓝色手机", argsCaptor.getValue()[0]);
        assertEquals("蓝色手机", argsCaptor.getValue()[1]);
        assertEquals("蓝色手机", argsCaptor.getValue()[2]);
        assertEquals("蓝色手机", argsCaptor.getValue()[3]);
        assertEquals("蓝色手机", argsCaptor.getValue()[4]);
        assertEquals("蓝色手机", argsCaptor.getValue()[5]);
        assertEquals(0.1, argsCaptor.getValue()[6]);
        assertEquals(5, argsCaptor.getValue()[7]);
    }
}
