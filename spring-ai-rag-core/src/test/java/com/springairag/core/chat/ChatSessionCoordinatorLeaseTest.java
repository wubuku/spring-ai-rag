package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话租约协调器单元测试：JdbcTemplate 桩子类记录 SQL/参数并按语句
 * 类型返回可配置的影响行数，锁定 acquire 竞争、deadline 到期、
 * commit 事务边界与 release/clear 的租约消费语义。
 */
class ChatSessionCoordinatorLeaseTest {

    private static final class StubJdbc extends JdbcTemplate {
        final Set<String> seenSql = new HashSet<>();
        String lastSql;
        Object[] lastArgs;
        int acquireResult = 1;

        private int resolve(String sql) {
            seenSql.add(marker(sql));
            if (sql.contains("INSERT INTO rag_chat_session_lease")) {
                return acquireResult;
            }
            return 1;
        }

        private String marker(String sql) {
            if (sql.contains("INSERT INTO rag_chat_session_lease")) return "acquire";
            if (sql.contains("SET expires_at")) return "renew";
            if (sql.contains("DELETE FROM rag_chat_session_lease")
                    && sql.contains("RETURNING")) return "consume";
            if (sql.contains("DELETE FROM rag_chat_session_lease")) return "release";
            return "other";
        }

        @Override
        public int update(String sql, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            return resolve(sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(
                String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper,
                Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            // consumeLease 的 RETURNING owner_token 单行结果。
            this.seenSql.add(sql.contains("RETURNING") ? "consume" : "other");
            return (List<T>) List.of("owner-token");
        }
    }

    private StubJdbc jdbc;
    private RagChatHistoryRepository historyRepository;
    private RagProperties properties;
    private ChatSessionCoordinator coordinator;

    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("local:test", "AUTH_DISABLED", false);

    @BeforeEach
    void setUp() {
        jdbc = new StubJdbc();
        historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.reserveDurableContentReferences(any(), any()))
                .thenReturn(new RagChatHistoryRepository
                        .DurableContentReferences(List.of(), Map.of()));
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        properties = new RagProperties();
        coordinator = new ChatSessionCoordinator(
                jdbc,
                historyRepository,
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                transactionManager,
                properties);
    }

    private ChatCommand command(String sessionId, MemoryMode memoryMode) {
        return new ChatCommand(
                "latest question",
                sessionId,
                PRINCIPAL,
                PRINCIPAL.memoryConversationId(sessionId),
                ChatMode.KNOWLEDGE,
                memoryMode,
                null,
                null,
                RetrievalScope.noMatches(),
                new RetrievalOptions(1, 0, false, false, 0, 0),
                Map.of());
    }

    private ChatExecutionResult result() {
        return new ChatExecutionResult(
                "answer",
                "session-1",
                "trace-1",
                null,
                null,
                ChatMode.KNOWLEDGE,
                List.of(),
                Map.of(),
                "STOP",
                List.of(),
                Map.of());
    }

    @Test
    void statelessAcquireSkipsTheLeaseRowEntirely() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.STATELESS), false);

        assertTrue(handle.deadline().isAfter(Instant.now()));
        assertEquals(false, jdbc.seenSql.contains("acquire"));
        // stateless 租约释放也不触库。
        coordinator.release(handle);
        assertEquals(false, jdbc.seenSql.contains("release"));
    }

    @Test
    void acquireBindsPrincipalSessionTokenAndTtl() {
        coordinator.acquire(command("session-1", MemoryMode.SERVER), false);

        assertEquals("acquire", jdbc.seenSql.iterator().next());
        assertEquals("local:test", jdbc.lastArgs[0]);
        assertEquals("session-1", jdbc.lastArgs[1]);
        assertTrue(((String) jdbc.lastArgs[2]).length() >= 32);
        assertTrue(((Number) jdbc.lastArgs[3]).longValue() >= 1_000);
    }

    @Test
    void acquireThrowsSessionBusyWhenTheLiveLeaseBlocksInsert() {
        jdbc.acquireResult = 0;

        RagException error = assertThrows(RagException.class,
                () -> coordinator.acquire(command("session-1", MemoryMode.SERVER), false));
        assertEquals(ErrorCode.SESSION_BUSY, error.getErrorCodeEnum());
    }

    @Test
    void invokeWithinDeadlineReturnsTheSupplierResult() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);

        String answer = coordinator.invokeWithinDeadline(handle, () -> "ok");

        assertEquals("ok", answer);
    }

    @Test
    void invokeWithinDeadlinePropagatesTheSupplierFailure() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> {
                    throw new IllegalStateException("model exploded");
                }));
        assertEquals("model exploded", failure.getMessage());
    }

    @Test
    void invokeWithinDeadlineThrowsTimeoutAfterTheDeadlinePasses() throws Exception {
        properties.getTimeout().setChatAskMs(1_000);
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        Thread.sleep(1_100);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> "late"));
        assertEquals(ErrorCode.CHAT_TIMEOUT, error.getErrorCodeEnum());
    }

    @Test
    void commitStatelessPersistsHistoryWithoutTouchingTheLease() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.STATELESS), false);

        coordinator.commit(handle, command("session-1", MemoryMode.STATELESS),
                result(), List.of(), "1,2");

        assertTrue(jdbc.seenSql.contains("other") || !jdbc.seenSql.contains("renew"));
        org.mockito.Mockito.verify(historyRepository).saveDurable(
                any(ChatPrincipal.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                any(RagChatHistoryRepository.DurableContentReferences.class));
    }

    @Test
    void commitStatefulRenewsTheLeaseInsideTheTransaction() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);

        coordinator.commit(handle, command("session-1", MemoryMode.SERVER),
                result(), List.of(), null);

        assertTrue(jdbc.seenSql.contains("renew"));
    }

    @Test
    void commitOperationWithoutRepositoryThrowsIdempotencyDisabled() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.STATELESS), false);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.commitOperation(
                        handle, null, command("session-1", MemoryMode.STATELESS),
                        result(), List.of(), null, null, null, null));
        assertEquals(ErrorCode.IDEMPOTENCY_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void failOperationWithoutRepositoryIsSilent() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.STATELESS), false);

        coordinator.failOperation(handle, null, "ERR", "{}");

        assertTrue(handle.deadline().isAfter(Instant.now().minus(Duration.ofSeconds(5))));
    }

    @Test
    void releaseDeletesTheLeaseRowWithOwnerBinding() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);

        coordinator.release(handle);

        // 最后一条语句是 DELETE 租约行。
        assertTrue(jdbc.lastSql.contains("DELETE FROM rag_chat_session_lease"));
        assertEquals("local:test", jdbc.lastArgs[0]);
        assertEquals("session-1", jdbc.lastArgs[1]);
        assertTrue(((String) jdbc.lastArgs[2]).length() >= 32);
    }

    @Test
    void clearSessionConsumesTheLeaseDeletesHistoryAndReturnsCount() {
        when(historyRepository.deleteByPrincipalAndSession(
                any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);

        int deleted = coordinator.clearSession(PRINCIPAL, "session-1");

        assertEquals(1, deleted);
        assertTrue(jdbc.seenSql.contains("acquire"));
        assertTrue(jdbc.seenSql.contains("consume"));
        assertTrue(jdbc.seenSql.contains("release"));
        org.mockito.Mockito.verify(historyRepository).deleteByPrincipalAndSession(
                PRINCIPAL, "session-1");
    }

    @Test
    void clearSessionThrowsSessionNotFoundWhenHistoryIsEmpty() {
        when(historyRepository.deleteByPrincipalAndSession(
                any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.clearSession(PRINCIPAL, "session-1"));
        assertEquals(ErrorCode.SESSION_NOT_FOUND, error.getErrorCodeEnum());
    }
}
