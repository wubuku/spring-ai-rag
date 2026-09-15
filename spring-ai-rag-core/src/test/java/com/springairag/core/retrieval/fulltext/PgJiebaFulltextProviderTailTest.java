package com.springairag.core.retrieval.fulltext;

import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgJiebaFulltextProvider 长尾（Batch 407）：空 query/matchNone
 * 短路、rank NULL 归零、excludeIds 对 embedding_id 与 local
 * chunk id 的两级排除、检索异常降级 failure、query 先 trim。
 */
class PgJiebaFulltextProviderTailTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // detectAvailability(): 扩展 + 配置返回 Integer，索引检测返回 Boolean。
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(
                contains("search_vector_zh"), eq(Boolean.class))).thenReturn(true);
    }

    private Map<String, Object> row(Object embeddingId, Object id,
                                    Object rank, long documentId) {
        Map<String, Object> row = new HashMap<>();
        if (embeddingId != null) {
            row.put("embedding_id", embeddingId);
        }
        row.put("id", id);
        row.put("chunk_text", "文本");
        row.put("document_id", documentId);
        row.put("chunk_index", 0);
        row.put("rank", rank);
        return row;
    }

    @Test
    void blankOrNullQueryShortCircuitsToEmptySuccess() {
        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);

        var nullQuery = provider.searchInScopeDetailed(
                null, RetrievalScope.unscoped(), null, 5, 0.0, 1L,
                RetrievalFilters.none());
        var blankQuery = provider.searchInScopeDetailed(
                "   ", RetrievalScope.unscoped(), null, 5, 0.0, 1L,
                RetrievalFilters.none());

        assertTrue(nullQuery.results().isEmpty());
        assertNull(nullQuery.errorCode());
        assertTrue(blankQuery.results().isEmpty());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void matchNoneScopeShortCircuitsToEmptySuccess() {
        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);

        var result = provider.searchInScopeDetailed(
                "spring", RetrievalScope.noMatches(), null, 5, 0.0, 1L,
                RetrievalFilters.none());

        assertTrue(result.results().isEmpty());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void excludedEmbeddingIdsAreFilteredAndNullRankMapsToZero() {
        when(jdbc.queryForList(contains("ts_rank"), any(Object[].class)))
                .thenReturn(List.of(
                        row(1L, 100L, 0.9, 11L),
                        row(2L, 200L, null, 22L)));
        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);

        var result = provider.searchInScopeDetailed(
                "spring", RetrievalScope.unscoped(), List.of(1L), 5, 0.0, 1L,
                RetrievalFilters.none());

        assertEquals(1, result.results().size());
        // 排除 embedding_id=1 后仅剩 rank=NULL 的行 → 分数归零。
        assertEquals(0.0, result.results().getFirst().getFulltextScore());
        assertEquals("22", result.results().getFirst().getDocumentId());
    }

    @Test
    void exclusionFallsBackToLocalChunkIdentity() {
        // 行 A：无 embedding_id 键、有 id → 按 id 排除。
        Map<String, Object> excludedById = row(null, 7L, 0.5, 70L);
        // 行 B：embedding_id 非 Number 且带 local_chunk_id → 不走 id 回退，保留。
        Map<String, Object> kept = row("not-a-number", 7L, 0.6, 70L);
        kept.put("local_chunk_id", 900L);
        when(jdbc.queryForList(contains("ts_rank"), any(Object[].class)))
                .thenReturn(List.of(excludedById, kept));
        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);

        var result = provider.searchInScopeDetailed(
                "spring", RetrievalScope.unscoped(), List.of(7L), 5, 0.0, 1L,
                RetrievalFilters.none());

        assertEquals(1, result.results().size());
        assertEquals(0.6, result.results().getFirst().getFulltextScore());
    }

    @Test
    void searchFailureDegradesToFailureResult() {
        when(jdbc.queryForList(contains("ts_rank"), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);

        var result = provider.searchInScopeDetailed(
                "spring", RetrievalScope.unscoped(), null, 5, 0.0, 1L,
                RetrievalFilters.none());

        assertTrue(result.results().isEmpty());
        assertEquals("DataAccessResourceFailureException", result.errorCode());
    }

    @Test
    void queryIsTrimmedBeforeExecutingSearch() {
        when(jdbc.queryForList(contains("ts_rank"), any(Object[].class)))
                .thenReturn(List.of());
        PgJiebaFulltextProvider provider = new PgJiebaFulltextProvider(jdbc);

        provider.searchInScopeDetailed(
                "  spring ai  ", RetrievalScope.unscoped(), null, 5, 0.0, 1L,
                RetrievalFilters.none());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), args.capture());
        assertEquals("spring ai", args.getValue()[0]);
        assertNotNull(args.getValue());
    }
}
