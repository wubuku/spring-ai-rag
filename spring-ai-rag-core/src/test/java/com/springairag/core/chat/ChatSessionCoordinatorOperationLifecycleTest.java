package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 键控回合操作的生命周期收尾：failOperation 的 active-worker CAS
 * （成功消费租约进入终态、租约丢失抛 CHAT_HISTORY_PERSIST_FAILED）、
 * failExpiredOperation 的专用 reclaim CAS、以及 clearSession 关联
 * 的会话摘要清理。
 */
class ChatSessionCoordinatorOperationLifecycleTest {

    private static final class StubJdbc extends JdbcTemplate {
        final Set<String> seenSql = new HashSet<>();

        private String marker(String sql) {
            if (sql.contains("INSERT INTO rag_chat_session_lease")) return "acquire";
            if (sql.contains("DELETE FROM rag_chat_session_lease")
                    && sql.contains("RETURNING")) return "consume";
            if (sql.contains("DELETE FROM rag_chat_session_lease")) return "release";
            return "other";
        }

        @Override
        public int update(String sql, Object... args) {
            seenSql.add(marker(sql));
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(
                String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper,
                Object... args) {
            seenSql.add(sql.contains("RETURNING") ? "consume" : "other");
            return (List<T>) List.of("owner-token");
        }
    }

    private StubJdbc jdbc;
    private RagChatHistoryRepository historyRepository;
    private ChatTurnOperationRepository operationRepository;
    private ConversationSummaryService summaryService;
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
        operationRepository = mock(ChatTurnOperationRepository.class);
        summaryService = mock(ConversationSummaryService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        coordinator = new ChatSessionCoordinator(
                jdbc,
                historyRepository,
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                transactionManager,
                new RagProperties());
        coordinator.setOperationRepository(operationRepository);
        coordinator.setSummaryService(summaryService);
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

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                PRINCIPAL.id(),
                "key-hash",
                "fingerprint-hash",
                1,
                "session-1",
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                now.plusSeconds(30),
                1,
                2L,
                1,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                null);
    }

    @Test
    void failOperationCompletesFailureConsumesLeaseAndTerminals() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        ChatTurnOperation operation = operation();
        when(operationRepository.completeFailure(
                eq(operation), eq("MODEL_FAILED"), eq("{}")))
                .thenReturn(true);

        coordinator.failOperation(handle, operation, "MODEL_FAILED", "{}");

        verify(operationRepository).completeFailure(
                operation, "MODEL_FAILED", "{}");
        // 状态化租约在失败收尾事务内被消费（非 lost，是显式 CAS 删除）。
        assertTrue(jdbc.seenSql.contains("consume"));
    }

    @Test
    void failOperationThrowsWhenOperationLeaseWasLost() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        ChatTurnOperation operation = operation();
        when(operationRepository.completeFailure(
                any(), anyString(), anyString())).thenReturn(false);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.failOperation(
                        handle, operation, "MODEL_FAILED", "{}"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void failExpiredOperationExhaustsAttemptsAndConsumesLease() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        ChatTurnOperation operation = operation();
        when(operationRepository.exhaustAttempts(
                eq(operation), eq("DEADLINE_EXCEEDED"), eq("{}")))
                .thenReturn(true);

        coordinator.failExpiredOperation(
                handle, operation, "DEADLINE_EXCEEDED", "{}");

        verify(operationRepository).exhaustAttempts(
                operation, "DEADLINE_EXCEEDED", "{}");
        assertTrue(jdbc.seenSql.contains("consume"));
    }

    @Test
    void failExpiredOperationThrowsWhenReclaimStateChanged() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        ChatTurnOperation operation = operation();
        when(operationRepository.exhaustAttempts(
                any(), anyString(), anyString())).thenReturn(false);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.failExpiredOperation(
                        handle, operation, "DEADLINE_EXCEEDED", "{}"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void clearSessionAlsoClearsTheConversationSummary() {
        when(historyRepository.deleteByPrincipalAndSession(
                any(), anyString())).thenReturn(4);

        int deleted = coordinator.clearSession(PRINCIPAL, "session-1");

        assertEquals(4, deleted);
        verify(summaryService).clear(PRINCIPAL, "session-1");
    }

    @Test
    void statelessFailOperationSkipsLeaseConsumption() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(
                        command("session-1", MemoryMode.STATELESS), false);
        ChatTurnOperation operation = operation();
        when(operationRepository.completeFailure(
                any(), anyString(), anyString())).thenReturn(true);

        coordinator.failOperation(handle, operation, "MODEL_FAILED", "{}");

        verify(operationRepository).completeFailure(
                operation, "MODEL_FAILED", "{}");
        // 无状态句柄没有租约行可消费。
        assertTrue(!jdbc.seenSql.contains("consume"));
    }
}
