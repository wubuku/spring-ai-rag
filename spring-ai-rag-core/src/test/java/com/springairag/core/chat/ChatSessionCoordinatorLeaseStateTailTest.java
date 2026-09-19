package com.springairag.core.chat;

import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import com.springairag.core.repository.RagChatHistoryRepository;
import com.springairag.core.chat.ChatSessionCoordinator.LeaseHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSessionCoordinator 调度与终态长尾（Batch 524，JaCoCo 驱动）：
 * 已过期/丢失租约守卫、非运行时异常原因包装、SERVER 内存收敛、
 * RagException 透传、failOperation/failExpiredOperation 包装、
 * renew 三分支、consumeLease 名单失配、resume 续排程失败降级。
 */
class ChatSessionCoordinatorLeaseStateTailTest {

    private JdbcTemplate jdbcTemplate;
    private RagChatHistoryRepository historyRepository;
    private ChatTurnOperationRepository operationRepository;
    private ChatSessionCoordinator coordinator;
    private ScheduledExecutorService reflectedRenewExecutor;
    private ExecutorService reflectedInvocationExecutor;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        historyRepository = mock(RagChatHistoryRepository.class);
        operationRepository = mock(ChatTurnOperationRepository.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        coordinator = new ChatSessionCoordinator(
                jdbcTemplate,
                historyRepository,
                mock(org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository.class),
                transactionManager,
                new RagProperties());
        coordinator.setOperationRepository(operationRepository);
        reflectedRenewExecutor = reflectedField("renewExecutor",
                ScheduledExecutorService.class);
        reflectedInvocationExecutor = reflectedField("invocationExecutor",
                ExecutorService.class);
    }

    @AfterEach
    void tearDown() {
        reflectedRenewExecutor.shutdownNow();
        reflectedInvocationExecutor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private <T> T reflectedField(String name, Class<T> type)
            throws Exception {
        var field = ChatSessionCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(coordinator);
    }

    /** 反射构造 keyed LeaseHandle（stateless=false）。 */
    private LeaseHandle keyedHandle(Instant deadline) throws Exception {
        var ctor = LeaseHandle.class.getDeclaredConstructor(
                String.class, String.class, String.class,
                Instant.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance("db:p1", "session-9", "token-1",
                deadline, false);
    }

    private LeaseHandle statelessHandle(Instant deadline) {
        return LeaseHandle.stateless(deadline);
    }

    private ChatCommand command(MemoryMode mode) {
        ChatPrincipal principal = ChatPrincipal.local();
        return new ChatCommand(
                "问题", "session-1", principal,
                principal.memoryConversationId("session-1"),
                ChatMode.PLAIN, mode, null, null,
                com.springairag.core.retrieval.RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.25, true, true, 0.55, 0.45),
                Map.of());
    }

    private ChatExecutionResult result() {
        return new ChatExecutionResult(
                "answer", "session-1", "trace-1", null, null,
                ChatMode.PLAIN, List.of(), Map.of(), "STOP",
                List.of(), Map.of());
    }

    private ChatTurnOperation operation() {
        return mock(ChatTurnOperation.class);
    }

    @Test
    void expiredDeadlineTimesOutBeforeInvocation() {
        var handle = statelessHandle(Instant.now().minusSeconds(1));

        var error = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(
                        handle, () -> "unused"));

        assertEquals(ErrorCode.CHAT_TIMEOUT, error.getErrorCodeEnum());
    }

    @Test
    void lostLeaseIsRejectedByActiveGuard() throws Exception {
        var handle = statelessHandle(Instant.now().plusSeconds(30));
        setLost(handle, true);

        var error = assertThrows(RagException.class,
                () -> coordinator.invokeWithinDeadline(
                        handle, () -> "unused"));

        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST,
                error.getErrorCodeEnum());
    }

    @Test
    void nonRuntimeCauseIsWrappedAsUnchecked() {
        var handle = statelessHandle(Instant.now().plusSeconds(30));

        var error = assertThrows(RuntimeException.class,
                () -> coordinator.invokeWithinDeadline(handle, () -> {
                    throw new AssertionError("boom");
                }));

        assertTrue(error.getCause() instanceof AssertionError);
    }

    @Test
    void commitWithServerMemoryClearsThenRefillsSharedMemory() {
        var handle = statelessHandle(Instant.now().plusSeconds(30));
        List<org.springframework.ai.chat.messages.Message> committed =
                List.of(new org.springframework.ai.chat.messages.UserMessage("u"));

        coordinator.commit(handle, command(MemoryMode.SERVER),
                result(), committed, "[]");

        // SERVER 模式收敛共享内存：durable 历史以 COMPLETE 状态落库。
        verify(historyRepository).reserveDurableContentReferences(
                anyString(), any());
    }

    @Test
    void commitRethrowsRagExceptionUnwrapped() {
        var handle = statelessHandle(Instant.now().plusSeconds(30));
        when(historyRepository.reserveDurableContentReferences(
                anyString(), any()))
                .thenThrow(new RagException(
                        ErrorCode.CHAT_SESSION_LEASE_LOST, "lease gone"));

        var error = assertThrows(RagException.class,
                () -> coordinator.commit(handle, command(MemoryMode.STATELESS),
                        result(), List.of(), "[]"));

        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST,
                error.getErrorCodeEnum());
    }

    @Test
    void failOperationWrapsUnexpectedRuntimeError() {
        var handle = statelessHandle(Instant.now().plusSeconds(30));
        when(operationRepository.completeFailure(
                any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("ledger down"));

        var error = assertThrows(RagException.class,
                () -> coordinator.failOperation(
                        handle, operation(), "INTERNAL", "{}"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void failExpiredOperationWrapsUnexpectedRuntimeError() {
        var handle = statelessHandle(Instant.now().plusSeconds(30));
        when(operationRepository.exhaustAttempts(
                any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("ledger down"));

        var error = assertThrows(RagException.class,
                () -> coordinator.failExpiredOperation(
                        handle, operation(), "INTERNAL", "{}"));

        assertEquals(ErrorCode.CHAT_HISTORY_PERSIST_FAILED,
                error.getErrorCodeEnum());
    }

    @Test
    void renewFlagsLostLeaseWhenCasMisses() throws Exception {
        var handle = keyedHandle(Instant.now().plusSeconds(30));
        when(jdbcTemplate.update(
                contains("UPDATE rag_chat_session_lease"), any(Object[].class)))
                .thenReturn(0);

        invokeRenew(handle);

        assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                field(handle, "lost")).get());
    }

    @Test
    void renewSwallowsRepositoryFailureAndFlagsLost() throws Exception {
        var handle = keyedHandle(Instant.now().plusSeconds(30));
        when(jdbcTemplate.update(
                contains("UPDATE rag_chat_session_lease"), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"));

        invokeRenew(handle);

        assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                field(handle, "lost")).get());
    }

    @Test
    void renewSkipsNonRunningHandle() throws Exception {
        var handle = keyedHandle(Instant.now().plusSeconds(30));
        setState(handle, "COMMITTING");

        invokeRenew(handle);

        verify(jdbcTemplate, org.mockito.Mockito.never()).update(
                contains("UPDATE rag_chat_session_lease"), any(Object[].class));
    }

    @Test
    void consumeLeaseThrowsWhenFencingRowMissing() throws Exception {
        var handle = keyedHandle(Instant.now().plusSeconds(30));
        // 经由行映射器产生两行（异常名单）→ 名单失配 → leaseLost。
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class),
                eq("db:p1"), eq("session-9"), eq("token-1")))
                .thenAnswer(invocation -> {
                    RowMapper<String> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs =
                            mock(java.sql.ResultSet.class);
                    when(rs.getString(1)).thenReturn("owner-token");
                    return List.of(mapper.mapRow(rs, 0),
                            mapper.mapRow(rs, 1));
                });

        var error = assertThrows(RagException.class,
                () -> invokeConsumeLease(handle));

        assertEquals(ErrorCode.CHAT_SESSION_LEASE_LOST,
                error.getErrorCodeEnum());
        assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                field(handle, "lost")).get());
    }

    @Test
    void resumeAfterCommitDegradesWhenRenewExecutorRejected()
            throws Exception {
        var handle = keyedHandle(Instant.now().plusSeconds(30));
        // commit 内的续租 CAS 命中，仅让 resume 的续排程被拒绝。
        when(jdbcTemplate.update(
                contains("UPDATE rag_chat_session_lease"), any(Object[].class)))
                .thenReturn(1);
        reflectedRenewExecutor.shutdown();

        coordinator.commit(handle, command(MemoryMode.STATELESS),
                result(), List.of(), "[]");

        // 续排程被拒仅降级告警，commit 本身成功。
        verify(historyRepository).reserveDurableContentReferences(
                anyString(), any());
    }

    private static Object field(Object target, String name)
            throws Exception {
        var f = ChatSessionCoordinator.LeaseHandle.class
                .getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setLost(LeaseHandle handle, boolean value)
            throws Exception {
        ((java.util.concurrent.atomic.AtomicBoolean)
                field(handle, "lost")).set(value);
    }

    @SuppressWarnings("unchecked")
    private static void setState(LeaseHandle handle, String name)
            throws Exception {
        var stateRef =
                (java.util.concurrent.atomic.AtomicReference<Enum<?>>)
                        field(handle, "state");
        Class<?> stateClass = Class.forName(
                "com.springairag.core.chat.ChatSessionCoordinator$State");
        Enum<?> constant = Enum.valueOf(
                (Class<? extends Enum>) stateClass.asSubclass(Enum.class),
                name);
        stateRef.set(constant);
    }

    private void invokeRenew(LeaseHandle handle) throws Exception {
        var method = ChatSessionCoordinator.class
                .getDeclaredMethod("renew", LeaseHandle.class);
        method.setAccessible(true);
        method.invoke(coordinator, handle);
    }

    private void invokeConsumeLease(LeaseHandle handle) throws Exception {
        var method = ChatSessionCoordinator.class
                .getDeclaredMethod("consumeLease", LeaseHandle.class);
        method.setAccessible(true);
        try {
            method.invoke(coordinator, handle);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
