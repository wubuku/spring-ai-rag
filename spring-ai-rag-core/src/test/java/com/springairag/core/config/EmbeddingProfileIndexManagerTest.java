package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖活动 Profile HNSW 索引管理：参数校验、幂等创建、无效索引重建、
 * 并发启动时等待另一个副本的构建，以及有界重试。
 */
class EmbeddingProfileIndexManagerTest {

    private JdbcTemplate jdbcTemplate;
    private Connection connection;
    private Statement statement;
    private EmbeddingProfileIndexManager manager;

    private final Deque<Boolean> validityAnswers = new ArrayDeque<>();
    private final Deque<Boolean> existenceAnswers = new ArrayDeque<>();
    private final Deque<Boolean> progressAnswers = new ArrayDeque<>();

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        manager = new EmbeddingProfileIndexManager(jdbcTemplate);

        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("indisvalid")))
                .thenAnswer(query -> answer(validityAnswers, false));
        when(statement.executeQuery(contains("relkind = 'i'")))
                .thenAnswer(query -> answer(existenceAnswers, true));
        when(statement.executeQuery(contains("pg_stat_progress_create_index")))
                .thenAnswer(query -> answer(progressAnswers, false));
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(
                (InvocationOnMock invocation) -> {
                    ConnectionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
    }

    private ResultSet answer(Deque<Boolean> answers, boolean fallback) throws Exception {
        return fixed(answers.isEmpty() ? fallback : answers.poll());
    }

    private ResultSet fixed(boolean value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(value);
        return rs;
    }

    private EmbeddingProfile profile(int dimensions) {
        return profile(7L, dimensions);
    }

    private EmbeddingProfile profile(long id, int dimensions) {
        return new EmbeddingProfile(id, "bge-m3", "siliconflow", "BAAI/bge-m3",
                "r1", dimensions, "COSINE", "PROVIDER_DEFAULT", true);
    }

    @Test
    void rejectsNonPositiveProfileId() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureIndex(profile(0L, 1024)));
    }

    @Test
    void rejectsUnsupportedDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureIndex(profile(512)));
    }

    @Test
    void skipsCreationWhenIndexAlreadyExistsAndValid() throws Exception {
        // 首次 indisvalid 查询返回 true → 直接返回，不执行 DDL。
        validityAnswers.add(true);

        manager.ensureIndex(profile(1024));

        verify(statement, never()).execute(anyString());
    }

    @Test
    void recreatesInvalidIndexAndConfirmsValidity() throws Exception {
        manager = new EmbeddingProfileIndexManager(jdbcTemplate, 1, 5L, 5_000L);
        validityAnswers.add(false);
        validityAnswers.add(false);
        validityAnswers.add(true);

        manager.ensureIndex(profile(1024));

        verify(statement).execute(contains("DROP INDEX CONCURRENTLY"));
        verify(statement).execute(contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS"));
    }

    @Test
    void waitsForAnotherReplicaBuildingTheSameIndexInsteadOfDroppingIt() throws Exception {
        manager = new EmbeddingProfileIndexManager(jdbcTemplate, 1, 5L, 500L);
        validityAnswers.add(false);
        validityAnswers.add(false);
        validityAnswers.add(true);
        progressAnswers.add(true);
        progressAnswers.add(true);

        manager.ensureIndex(profile(1024));

        verify(statement, never()).execute(anyString());
    }

    @Test
    void throwsWhenIndexStillInvalidAfterCreation() {
        manager = new EmbeddingProfileIndexManager(jdbcTemplate, 1, 5L, 5_000L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.ensureIndex(profile(1024)));
        assertTrue(error.getMessage().startsWith("Embedding profile index is not valid"));
    }

    @Test
    void timesOutWhenAnotherReplicaBuildNeverFinishes() throws Exception {
        manager = new EmbeddingProfileIndexManager(jdbcTemplate, 1, 5L, 40L);
        validityAnswers.add(false);
        when(statement.executeQuery(contains("pg_stat_progress_create_index")))
                .thenAnswer(query -> fixed(true));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.ensureIndex(profile(1024)));
        assertTrue(error.getMessage().contains("Timed out waiting"));
    }

    @Test
    void retriesTransientFailuresUpToThreeAttempts() throws Exception {
        // 索引查询按有效索引应答；execute 级覆盖注入两次瞬态失败。
        validityAnswers.add(true);
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new RuntimeException("lock timeout"))
                .thenThrow(new RuntimeException("lock timeout"))
                .thenAnswer(invocation -> {
                    ConnectionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });

        manager.ensureIndex(profile(1024));

        verify(jdbcTemplate, times(3)).execute(any(ConnectionCallback.class));
    }

    @Test
    void givesUpAfterThreeFailedAttemptsAndThrowsLastFailure() {
        RuntimeException lastFailure = new RuntimeException("still failing");
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new RuntimeException("first"))
                .thenThrow(new RuntimeException("second"))
                .thenThrow(lastFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> manager.ensureIndex(profile(1024)));
        assertEquals("still failing", thrown.getMessage());
    }

    @Test
    void propagatesInterruptionDuringRetryBackoff() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new RuntimeException("first"));
        // 预置中断标志，使重试退避的 sleep 立即抛 InterruptedException。
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.ensureIndex(profile(1024)));
        assertTrue(error.getMessage().contains("Interrupted"));
        assertTrue(Thread.interrupted());
    }
}
