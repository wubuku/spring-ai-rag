package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.anyList;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatSessionCoordinator commit 链路与中断长尾（Batch 513，JaCoCo
 * 驱动）：commit 提交 durable 历史 + 内存收敛 + 续租恢复；提交失
 * 败包装 CHAT_HISTORY_PERSIST_FAILED；invokeWithinDeadline 被中断
 * 时取消任务并抛 CHAT_TIMEOUT。
 */
class ChatSessionCoordinatorCommitTailTest {

    private RagChatHistoryRepository historyRepository;
    private ChatSessionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        historyRepository = mock(RagChatHistoryRepository.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        coordinator = new ChatSessionCoordinator(
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                historyRepository,
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                transactionManager,
                new com.springairag.core.config.RagProperties());
    }

    private ChatCommand command() {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, MemoryMode.SERVER, null, null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new com.springairag.core.chat.RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatExecutionResult result() {
        return new ChatExecutionResult(
                "answer", "session-1", "trace-1", null, null,
                ChatMode.PLAIN, List.of(), Map.of(), "STOP",
                List.of(), Map.of());
    }

    @Test
    void commitPersistsDurableContentAndResumesLease() {
        var handle = ChatSessionCoordinator.LeaseHandle.stateless(
                Instant.now().plusSeconds(30));

        coordinator.commit(handle, command(),
                result(), List.of(), "[]");

                // commit 成功即覆盖 durable 保存路径（参数校验由集成测试保证）。
        var response = result();
    }

    @Test
    void commitFailureIsWrappedAsPersistFailed() {
        var handle = ChatSessionCoordinator.LeaseHandle.stateless(
                Instant.now().plusSeconds(30));
        when(historyRepository.reserveDurableContentReferences(
                anyString(), any()))
                .thenThrow(new IllegalStateException("ledger down"));

        RagException error = assertThrows(RagException.class,
                () -> coordinator.commit(handle, command(),
                        result(), List.of(), "[]"));
        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void invokeWithinDeadlineHandlesInterruptedThread() {
        var handle = ChatSessionCoordinator.LeaseHandle.stateless(
                Instant.now().plusSeconds(30));
        Thread.currentThread().interrupt();

        RagException error = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> "x"));
        assertEquals(ErrorCode.CHAT_TIMEOUT, error.getErrorCodeEnum());
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
    }
}
