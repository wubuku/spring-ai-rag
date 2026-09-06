package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
 * 创建后校验失败与瞬态失败的有界重试。
 */
class EmbeddingProfileIndexManagerTest {

    private JdbcTemplate jdbcTemplate;
    private Connection connection;
    private Statement statement;
    private EmbeddingProfileIndexManager manager;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        manager = new EmbeddingProfileIndexManager(jdbcTemplate);
    }

    private EmbeddingProfile profile(int dimensions) {
        return profile(7L, dimensions);
    }

    private EmbeddingProfile profile(long id, int dimensions) {
        return new EmbeddingProfile(id, "bge-m3", "siliconflow", "BAAI/bge-m3",
                "r1", dimensions, "COSINE", "PROVIDER_DEFAULT", true);
    }

    /** 把 mock Connection 交给 ConnectionCallback，并按序列应答 Statement 查询。 */
    private void stubCallback(Deque<Boolean> booleanResults) throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        // 每次布尔查询按序列取值；序列耗尽或未提供时安全返回 false。
        when(statement.executeQuery(anyString())).thenAnswer(query -> {
            ResultSet rs = mock(ResultSet.class);
            boolean value = booleanResults != null && !booleanResults.isEmpty()
                    ? booleanResults.poll()
                    : false;
            when(rs.next()).thenReturn(true);
            when(rs.getBoolean(1)).thenReturn(value);
            return rs;
        });
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(
                (InvocationOnMock invocation) -> {
                    ConnectionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
    }

    @Test
    void rejectsNonPositiveProfileId() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureIndex(profile(0L, 1024)));
    }

    @Test
    void rejectsUnsupportedDimensions() throws Exception {
        stubCallback(null);

        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureIndex(profile(512)));
    }

    @Test
    void skipsCreationWhenIndexAlreadyExistsAndValid() throws Exception {
        // 首次 indisvalid 查询返回 true → 直接返回，不执行 DDL。
        stubCallback(new ArrayDeque<>(List.of(true)));

        manager.ensureIndex(profile(1024));

        verify(statement, never()).execute(anyString());
    }

    @Test
    void recreatesInvalidIndexAndConfirmsValidity() throws Exception {
        stubCallback(new ArrayDeque<>(List.of(false, true, true)));

        manager.ensureIndex(profile(1024));

        verify(statement).execute(contains("DROP INDEX CONCURRENTLY"));
        verify(statement).execute(contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS"));
    }

    @Test
    void throwsWhenIndexStillInvalidAfterCreation() throws Exception {
        // 三次 indisvalid 查询均为 false：预检、exists 检查、创建后校验。
        stubCallback(new ArrayDeque<>(List.of(false, false, false)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.ensureIndex(profile(1024)));
        assertTrue(error.getMessage().startsWith("Embedding profile index is not valid"));
    }

    @Test
    void retriesTransientFailuresUpToThreeAttempts() throws Exception {
        // 索引查询按有效索引应答；execute 级覆盖注入两次瞬态失败。
        stubCallback(new ArrayDeque<>(List.of(true)));
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
    void givesUpAfterThreeFailedAttemptsAndThrowsLastFailure() throws Exception {
        stubCallback(null);
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
    void propagatesInterruptionDuringRetryBackoff() throws Exception {
        stubCallback(null);
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
