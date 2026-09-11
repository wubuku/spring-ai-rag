package com.springairag.core.evaluation;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * lookup 的身份映射语义：非数字 documentId 跳过、查询占位符与输
 * 入 id 一一对应、按输入 id 顺序去重输出、external_id 为空的行剔
 * 除。
 */
class EvaluationCaseExecutorLookupTest {

    private JdbcTemplate jdbcTemplate;
    private EvaluationCaseExecutor executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        executor = new EvaluationCaseExecutor(
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                jdbcTemplate);
    }

    private RetrievalResult result(String documentId) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        return result;
    }

    /** 模拟 jdbc 行回调：为每个 id 注册一条 (collection_key, namespace, external_id) 记录。 */
    private void stubLookupRows(java.util.LinkedHashMap<Long, String[]> rows) {
        org.mockito.Mockito.doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            Object[] raw = invocation.getArguments();
            for (int i = 2; i < raw.length; i++) {
                long id = ((Number) raw[i]).longValue();
                String[] values = rows.get(id);
                if (values == null) {
                    continue;
                }
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                Mockito.lenient().when(rs.getLong("id")).thenReturn(id);
                Mockito.lenient().when(rs.getString("collection_key"))
                        .thenReturn(values[0]);
                Mockito.lenient().when(rs.getString("source_namespace"))
                        .thenReturn(values[1]);
                Mockito.lenient().when(rs.getString("external_id"))
                        .thenReturn(values[2]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(
                anyString(),
                any(RowCallbackHandler.class),
                any(Object[].class));
    }

    @Test
    void lookupSkipsNonNumericIdsAndPreservesInputOrder() {
        var rows = new java.util.LinkedHashMap<Long, String[]>();
        rows.put(5L, new String[]{"kb", "default", "ext-5"});
        rows.put(9L, new String[]{"kb", "default", "ext-9"});
        stubLookupRows(rows);

        List<EvaluationSuiteDefinition.Identity> identities =
                executor.lookup(List.of(
                        result("not-numeric"), result("5"), result("9")));

        assertEquals(2, identities.size());
        assertEquals("ext-5", identities.get(0).externalId());
        assertEquals("ext-9", identities.get(1).externalId());
        // 占位符数量与输入 id 数一致。
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowCallbackHandler.class),
                any(Object[].class));
        assertTrue(sqlCaptor.getValue().contains("IN (?,?)"));
    }

    @Test
    void lookupDeduplicatesAndDropsBlankExternalId() {
        // id 5 出现两次（输入顺序保留一次）；id 7 的 external_id 为空被剔除。
        var rows = new java.util.LinkedHashMap<Long, String[]>();
        rows.put(5L, new String[]{"kb", "default", "ext-5"});
        rows.put(7L, new String[]{"kb", "default", ""});
        stubLookupRows(rows);

        List<EvaluationSuiteDefinition.Identity> identities =
                executor.lookup(List.of(
                        result("5"), result("5"), result("7")));

        assertEquals(1, identities.size());
        assertEquals("ext-5", identities.get(0).externalId());
    }

    @Test
    void lookupWithNoParsableIdsReturnsEmptyWithoutQuery() {
        List<EvaluationSuiteDefinition.Identity> identities =
                executor.lookup(List.of(result("abc")));

        assertTrue(identities.isEmpty());
        verify(jdbcTemplate, never()).query(
                anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }
}
