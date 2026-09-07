package com.springairag.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 反馈文档引用轻量 JDBC 存储：参数化 IN 查询与批量插入绑定。 */
class FeedbackDocumentReferenceStoreTest {

    private static final class StubJdbc extends JdbcTemplate {
        String lastSql;
        Object[] lastArgs;
        int queryCalls;

        @Override
        public <T> List<T> query(
                String sql, RowMapper<T> rowMapper, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            this.queryCalls++;
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("id")).thenReturn(7L);
                when(rs.getObject("collection_id", Long.class)).thenReturn(3L);
                when(rs.getBoolean("enabled")).thenReturn(true);
                return List.of(rowMapper.mapRow(rs, 0));
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private StubJdbc jdbc;
    private FeedbackDocumentReferenceStore store;

    @BeforeEach
    void setUp() {
        jdbc = new StubJdbc();
        store = new FeedbackDocumentReferenceStore(jdbc);
    }

    @Test
    void loadReturnsEmptyForNullOrEmptyIdsWithoutQuerying() {
        assertTrue(store.load(null).isEmpty());
        assertEquals(0, jdbc.queryCalls);

        assertTrue(store.load(List.of()).isEmpty());
        assertEquals(0, jdbc.queryCalls);
    }

    @Test
    void loadBuildsParameterizedInClauseAndMapsSnapshots() {
        List<FeedbackDocumentReferenceStore.DocumentSnapshot> snapshots =
                store.load(List.of(7L, 9L));

        assertEquals(1, snapshots.size());
        assertEquals(7L, snapshots.get(0).documentId());
        assertEquals(3L, snapshots.get(0).collectionId());
        assertTrue(snapshots.get(0).enabled());
        // 参数化 IN 占位符与 ids 数量一致。
        assertTrue(jdbc.lastSql.contains("IN (?,?)"));
        assertEquals(2, jdbc.lastArgs.length);
    }

    @Test
    void insertSkipsEmptyFeedbackReferences() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);

        new FeedbackDocumentReferenceStore(mockJdbc).insert(77L, List.of());

        org.mockito.Mockito.verifyNoInteractions(mockJdbc);
    }

    @Test
    void insertBindsFeedbackIdAndEachDocumentId() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<List<Long>> items = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ParameterizedPreparedStatementSetter<Long>> setter =
                ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);

        new FeedbackDocumentReferenceStore(mockJdbc)
                .insert(77L, List.of(1L, 2L, 3L));

        org.mockito.Mockito.verify(mockJdbc).batchUpdate(
                org.mockito.ArgumentMatchers.contains("rag_user_feedback_document"),
                items.capture(), org.mockito.ArgumentMatchers.eq(3),
                setter.capture());
        assertEquals(List.of(1L, 2L, 3L), items.getValue());
    }
}
