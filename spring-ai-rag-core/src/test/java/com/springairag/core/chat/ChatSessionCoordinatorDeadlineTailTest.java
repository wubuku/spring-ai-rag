package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSessionCoordinator 截止时间与过期操作长尾（Batch 488，JaCoCo
 * 驱动）：invokeWithinDeadline 的空句柄拒绝、过期截止、正常返回、
 * 供应商运行时异常透传与超时取消；failExpiredOperation 的仓储缺
 * 省直返、耗尽成功终止句柄与回收竞争失败。
 */
class ChatSessionCoordinatorDeadlineTailTest {

    private JdbcTemplate jdbcTemplate;
    private ChatTurnOperationRepository operationRepository;
    private ChatSessionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        operationRepository = mock(ChatTurnOperationRepository.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        coordinator = new ChatSessionCoordinator(
                jdbcTemplate,
                mock(RagChatHistoryRepository.class),
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                transactionManager,
                new RagProperties());
        coordinator.setOperationRepository(operationRepository);
    }

    private ChatSessionCoordinator.LeaseHandle statelessHandle(
            Instant deadline) {
        return ChatSessionCoordinator.LeaseHandle.stateless(deadline);
    }

    private ChatTurnOperation operation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L, "principal-1", "key-hash", "fp-hash", 1,
                "session-1", UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(), now.plusSeconds(60),
                8, 0L, 1,
                null, null, null, null, "{}",
                now, now, null);
    }

    @Test
    void invokeWithinDeadlineRejectsNullHandle() {
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.invokeWithinDeadline(null, () -> "x"));
    }

    @Test
    void invokeWithinDeadlineRejectsExpiredDeadline() {
        ChatSessionCoordinator.LeaseHandle handle =
                statelessHandle(Instant.now().minusMillis(1));

        RagException error = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> "x"));
        assertEquals(ErrorCode.CHAT_TIMEOUT, error.getErrorCodeEnum());
    }

    @Test
    void invokeWithinDeadlineReturnsSupplierResult() {
        ChatSessionCoordinator.LeaseHandle handle =
                statelessHandle(Instant.now().plusSeconds(30));

        String result = coordinator.invokeWithinDeadline(handle, () -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void invokeWithinDeadlinePropagatesSupplierRuntimeFailure() {
        ChatSessionCoordinator.LeaseHandle handle =
                statelessHandle(Instant.now().plusSeconds(30));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> coordinator.invokeWithinDeadline(
                        handle, () -> {
                            throw new IllegalStateException("model boom");
                        }));

        assertEquals("model boom", error.getMessage());
    }

    @Test
    void invokeWithinDeadlineCancelsFutureOnTimeout() {
        ChatSessionCoordinator.LeaseHandle handle =
                statelessHandle(Instant.now().plusMillis(80));

        RagException error = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> {
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                }));
        assertEquals(ErrorCode.CHAT_TIMEOUT, error.getErrorCodeEnum());
    }

    @Test
    void failExpiredOperationWithoutRepositoryIsNoOp() {
        ChatSessionCoordinator bare = new ChatSessionCoordinator(
                jdbcTemplate,
                mock(RagChatHistoryRepository.class),
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                mock(PlatformTransactionManager.class),
                new RagProperties());

        bare.failExpiredOperation(
                statelessHandle(Instant.now().plusSeconds(30)),
                operation(), "INTERNAL_ERROR", "{}");

        verify(operationRepository, never()).exhaustAttempts(
                any(), anyString(), anyString());
    }

    @Test
    void failExpiredOperationExhaustsAttemptsAndTerminates() {
        ChatSessionCoordinator.LeaseHandle handle =
                statelessHandle(Instant.now().plusSeconds(30));
        ChatTurnOperation operation = operation();
        when(operationRepository.exhaustAttempts(
                eq(operation), eq("INTERNAL_ERROR"), eq("{}")))
                .thenReturn(true);

        coordinator.failExpiredOperation(handle, operation,
                "INTERNAL_ERROR", "{}");

        verify(operationRepository).exhaustAttempts(
                eq(operation), eq("INTERNAL_ERROR"), eq("{}"));
    }

    @Test
    void failExpiredOperationReclaimConflictPersists() {
        ChatSessionCoordinator.LeaseHandle handle =
                statelessHandle(Instant.now().plusSeconds(30));
        when(operationRepository.exhaustAttempts(
                any(), anyString(), anyString())).thenReturn(false);

        RagException error = assertThrows(RagException.class,
                () -> coordinator.failExpiredOperation(
                        handle, operation(), "INTERNAL_ERROR", "{}"));
        assertTrue(error.getMessage().contains("reclaim state changed"));
    }

}
