package com.springairag.core.evaluation;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvaluationCaseExecutor 长尾（Batch 526，JaCoCo 驱动）：双参构造
 * 器、rerank 缺服务拒绝、identityExists 双参委托与计数判定、快照
 * 的每键统计与 null 时间戳分支。
 */
class EvaluationCaseExecutorSnapshotTailTest {

    private JdbcTemplate jdbcTemplate;
    private HybridRetrieverService retrieverService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        retrieverService = mock(HybridRetrieverService.class);
    }

    private EvaluationCaseExecutor executor() {
        return new EvaluationCaseExecutor(
                retrieverService, jdbcTemplate);
    }

    private RetrievalResult result(String documentId) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        return result;
    }

    @Test
    void rerankWithoutServiceIsRejected() {
        RetrievalConfig config = new RetrievalConfig();
        config.setUseRerank(true);
        when(retrieverService.searchInScopeDetailed(
                any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of(result("1"))));

        var error = assertThrows(IllegalStateException.class,
                () -> executor().search(
                        "q", RetrievalScope.unscoped(), config,
                        RetrievalFilters.none()));

        assertEquals("ReRankingService is required for rerank evaluation",
                error.getMessage());
    }

    @Test
    void identityExistsTwoArgDelegateMatchesCount() {
        when(jdbcTemplate.queryForObject(
                contains("AND d.external_id = ?"), eq(Integer.class),
                eq("kb"), eq("default"), eq("doc-1")))
                .thenReturn(2)
                .thenReturn(0);

        assertTrue(executor().identityExists("kb", "doc-1"));
        assertFalse(executor().identityExists("kb", "doc-1"));
    }

    @Test
    void identityExistsTreatsNullCountAsMissing() {
        when(jdbcTemplate.queryForObject(
                contains("AND d.external_id = ?"), eq(Integer.class),
                any(), any(), any()))
                .thenReturn(null);

        assertFalse(executor().identityExists("kb", "ns", "missing"));
    }

    @Test
    void collectionSnapshotBuildsPerKeyStatistics() {
        // 第一个键：时间戳非空；第二个键：时间戳为 NULL。
        Timestamp ts = Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"));
        Mockito.doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            String key = (String) invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("enabled_count"))
                    .thenReturn("kb-a".equals(key) ? 5L : 0L);
            when(rs.getTimestamp("max_updated_at"))
                    .thenReturn("kb-a".equals(key) ? ts : null);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(
                contains("FILTER (WHERE d.enabled)"),
                any(RowCallbackHandler.class),
                any(Object[].class));

        Map<String, Object> snapshot =
                executor().collectionSnapshot(List.of("kb-a", "kb-b"));

        assertEquals(2, snapshot.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) snapshot.get("kb-a");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) snapshot.get("kb-b");
        assertEquals(5L, first.get("enabledDocuments"));
        assertEquals("2026-09-01T00:00:00Z", first.get("maxUpdatedAt"));
        assertEquals(0L, second.get("enabledDocuments"));
        assertNullValue(second.get("maxUpdatedAt"));
    }

    private static void assertNullValue(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void collectionSnapshotEmptyKeysYieldsEmptyMap() {
        assertTrue(
                executor().collectionSnapshot(List.of()).isEmpty());
    }
}
