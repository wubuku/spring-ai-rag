package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * commitOperation 持久提交事务：引用预留、状态化租约续期、CAS 完
 * 成、持久历史写入、SERVER 共享记忆刷新、CLIENT 跳过记忆、租约丢
 * 失与仓储缺失的失败语义。
 */
class ChatSessionCoordinatorCommitOperationTest {

    private static final ChatPrincipal PRINCIPAL =
            new ChatPrincipal("db:key-1", "DATABASE_API_KEY", false);

    /** 记录已执行 SQL 的 JdbcTemplate 桩（按片段返回命中行数）。 */
    private static class StubJdbc extends JdbcTemplate {
        final List<String> seenSql = new ArrayList<>();
        int renewAffected = 1;

        @Override
        public int update(String sql, Object... args) {
            seenSql.add(sql);
            if (sql.contains("UPDATE rag_chat_session_lease")) {
                return renewAffected;
            }
            return 1;
        }

        @Override
        public <T> T execute(org.springframework.jdbc.core.ConnectionCallback<T> action) {
            try {
                return action.doInConnection(mock(java.sql.Connection.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private StubJdbc jdbc;
    private RagChatHistoryRepository historyRepository;
    private ChatMemoryRepository memoryRepository;
    private ChatTurnOperationRepository operationRepository;
    private ChatSessionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jdbc = new StubJdbc();
        historyRepository = mock(RagChatHistoryRepository.class);
        when(historyRepository.reserveDurableContentReferences(any(), any()))
                .thenReturn(new RagChatHistoryRepository
                        .DurableContentReferences(List.of(), Map.of()));
        operationRepository = mock(ChatTurnOperationRepository.class);
        when(operationRepository.completeSuccess(
                any(), anyString(), anyString(), anyString())).thenReturn(true);
        memoryRepository = mock(ChatMemoryRepository.class);
        when(memoryRepository.findByConversationId(anyString()))
                .thenReturn(List.of());
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        coordinator = new ChatSessionCoordinator(
                jdbc,
                historyRepository,
                mock(JdbcChatMemoryRepository.class),
                transactionManager,
                new RagProperties());
        coordinator.setOperationRepository(operationRepository);
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
                "final answer", "session-1", "trace-1",
                null, null, ChatMode.KNOWLEDGE,
                List.of(), Map.of(), "STOP", List.of(), Map.of());
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, PRINCIPAL.id(), "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                1, 0L, 1,
                "{\"executionSnapshotVersion\":1}", null, null, null, "{}",
                now, now, null);
    }

    @Test
    void statefulServerMemoryCommitPersistsAllArtifacts() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        ChatTurnOperation operation = operation();

        coordinator.commitOperation(
                handle, operation, command("session-1", MemoryMode.SERVER),
                result(), List.of(new UserMessage("hello")),
                "[41]", "{\"executionSnapshotVersion\":1}",
                "{\"answer\":\"final answer\"}", "{\"authorization\":1}");

        verify(operationRepository).completeSuccess(
                operation,
                "{\"executionSnapshotVersion\":1}",
                "{\"answer\":\"final answer\"}",
                "{\"authorization\":1}");
        verify(historyRepository).saveDurable(
                eq(PRINCIPAL), eq("session-1"), eq("latest question"),
                eq("final answer"), eq("[41]"), any(),
                eq("COMPLETE"), any(), eq(operation.turnId()), any());
        assertTrue(jdbc.seenSql.stream()
                .anyMatch(sql -> sql.contains("UPDATE rag_chat_session_lease")));
    }

    @Test
    void statelessCommitSkipsLeaseRenewal() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.STATELESS), true);

        coordinator.commitOperation(
                handle, operation(), command("session-1", MemoryMode.STATELESS),
                result(), List.of(), null, "{}", "{}", "{}");

        verify(historyRepository).saveDurable(
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        assertTrue(jdbc.seenSql.stream()
                .noneMatch(sql -> sql.contains("UPDATE rag_chat_session_lease")));
    }

    @Test
    void completeSuccessFalseFailsClosedWithoutSavingHistory() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        ChatTurnOperation operation = operation();
        when(operationRepository.completeSuccess(
                any(), anyString(), anyString(), anyString())).thenReturn(false);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.commitOperation(
                        handle, operation, command("session-1", MemoryMode.SERVER),
                        result(), List.of(), null, "{}", "{}", "{}"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
        verify(historyRepository, never()).saveDurable(
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void renewLeaseLostDuringCommitSurfacesLeaseLost() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        jdbc.renewAffected = 0;

        RagException error = assertThrows(RagException.class,
                () -> coordinator.commitOperation(
                        handle, operation(), command("session-1", MemoryMode.SERVER),
                        result(), List.of(), null, "{}", "{}", "{}"));

        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST,
                error.getErrorCodeEnum());
        verify(historyRepository, never()).saveDurable(
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void statelessCommitSkipsSharedMemoryWrites() {
        // STATELESS：不写共享记忆（sharedMemory 仅在 SERVER 模式刷新）。
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.STATELESS), false);

        coordinator.commitOperation(
                handle, operation(), command("session-1", MemoryMode.STATELESS),
                result(), List.of(new UserMessage("hello")),
                null, "{}", "{}", "{}");

        verify(memoryRepository, never()).saveAll(anyString(), anyList());
        verify(memoryRepository, never()).deleteByConversationId(anyString());
    }

    @Test
    void runtimeFailureWrappedAsPersistFailed() {
        ChatSessionCoordinator.LeaseHandle handle =
                coordinator.acquire(command("session-1", MemoryMode.SERVER), false);
        when(historyRepository.saveDurable(
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("history broken"));

        RagException error = assertThrows(RagException.class,
                () -> coordinator.commitOperation(
                        handle, operation(), command("session-1", MemoryMode.SERVER),
                        result(), List.of(), null, "{}", "{}", "{}"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void missingOperationRepositoryThrowsIdempotencyDisabled() {
        // 无 operationRepository 的独立实例（经两条参构造器注入依赖）。
        ChatSessionCoordinator bare = new ChatSessionCoordinator(
                jdbc,
                historyRepository,
                mock(JdbcChatMemoryRepository.class),
                mock(PlatformTransactionManager.class),
                new RagProperties());

        RagException error = assertThrows(RagException.class,
                () -> bare.commitOperation(
                        coordinator.acquire(
                                command("session-1", MemoryMode.STATELESS), false),
                        operation(), command("session-1", MemoryMode.SERVER),
                        result(), List.of(), null, "{}", "{}", "{}"));

        assertEquals(ErrorCode.IDEMPOTENCY_DISABLED, error.getErrorCodeEnum());
    }
}
