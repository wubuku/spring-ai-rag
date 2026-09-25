package com.springairag.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnOperationService 续约任务长尾（Batch 642，JaCoCo 驱
 * 动）：以捕获型 ScheduledExecutorService 同步驱动续约 Runnable，
 * 覆盖 renew 成功后的 claim.updateOperation、renew 返回 null 的
 * renewalLost 标记，以及 stopRenewal 后任务体的 renewalStopped
 * 短路。
 */
class ChatTurnOperationRenewalTaskTailTest {

    private ChatTurnOperationRepository repository;
    private ChatTurnOperationService service;
    private Runnable renewalTask;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(ChatTurnOperationRepository.class);
        service = new ChatTurnOperationService(
                repository,
                new ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(com.springairag.core.chat.ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));

        // 用捕获型执行器替换内联调度器，同步驱动续约任务体。
        ScheduledExecutorService capturing =
                mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(capturing.scheduleAtFixedRate(any(Runnable.class),
                anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    renewalTask = invocation.getArgument(0);
                    return future;
                });
        Field field = ChatTurnOperationService.class
                .getDeclaredField("renewalExecutor");
        field.setAccessible(true);
        field.set(service, capturing);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private ChatTurnOperation runningOperation() {
        Instant now = Instant.now();
        return new ChatTurnOperation(
                1L,
                ChatPrincipal.local().id(),
                "key-hash",
                "fingerprint-hash",
                1,
                "session-1",
                UUID.randomUUID(),
                ChatTurnOperation.Transport.NATIVE_JSON,
                ChatTurnOperation.Status.IN_PROGRESS,
                UUID.randomUUID(),
                now.plusSeconds(60),
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

    private ChatTurnOperationService.Claim keyedClaim(
            ChatTurnOperation operation) throws Exception {
        Constructor<ChatTurnOperationService.Claim> ctor =
                ChatTurnOperationService.Claim.class.getDeclaredConstructor(
                        ChatTurnOperation.class, boolean.class,
                        ChatSessionCoordinator.LeaseHandle.class);
        ctor.setAccessible(true);
        return ctor.newInstance(operation, false, null);
    }

    @Test
    void renewalTaskSwapsClaimToRenewedOperation() throws Exception {
        ChatTurnOperation original = runningOperation();
        ChatTurnOperationService.Claim claim = keyedClaim(original);
        service.startRenewal(claim);

        assertNotNull(renewalTask);
        ChatTurnOperation renewed = new ChatTurnOperation(
                original.id(),
                original.ownerPrincipalId(),
                original.idempotencyKeySha256(),
                original.requestFingerprintSha256(),
                original.fingerprintVersion(),
                original.sessionId(),
                original.turnId(),
                original.transport(),
                original.status(),
                original.operationToken(),
                original.leaseExpiresAt(),
                original.attemptCount(),
                original.rowVersion() + 1,
                original.responseVersion(),
                original.executionSnapshot(),
                original.responsePayload(),
                original.errorCode(),
                original.errorPayload(),
                original.authorizationScopeSnapshot(),
                original.createdAt(),
                original.updatedAt(),
                original.completedAt());
        when(repository.renew(same(original), anyInt())).thenReturn(renewed);

        renewalTask.run();

        assertSame(renewed, claim.operation());
        assertNotSame(original, claim.operation());
        assertFalse(claim.renewalLost());
        service.release(claim);
    }

    @Test
    void renewalTaskMarksLossWhenRenewReturnsNull() throws Exception {
        ChatTurnOperation original = runningOperation();
        ChatTurnOperationService.Claim claim = keyedClaim(original);
        service.startRenewal(claim);

        assertNotNull(renewalTask);
        when(repository.renew(same(original), anyInt())).thenReturn(null);

        renewalTask.run();

        assertTrue(claim.renewalLost());
        assertSame(original, claim.operation());
        service.release(claim);
    }

    @Test
    void stoppedRenewalTaskShortCircuitsBeforeRepositoryCall()
            throws Exception {
        ChatTurnOperation original = runningOperation();
        ChatTurnOperationService.Claim claim = keyedClaim(original);
        service.startRenewal(claim);

        assertNotNull(renewalTask);
        service.release(claim);

        renewalTask.run();

        verify(repository, never()).renew(any(), anyInt());
        assertEquals(original, claim.operation());
    }
}
