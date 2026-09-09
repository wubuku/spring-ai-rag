package com.springairag.core.chat;

import com.springairag.core.config.RagChatProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.ChatTurnOperationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * startRenewal/stopRenewal 调度语义：非键控与 replay 声明不调度、
 * 键控声明创建周期续期任务、重复启动不重复调度、停止后任务取消。
 * （周期体首轮触发 ≥10s，renew CAS 的成败分支由仓储层保证，此处锁
 * 定调度创建与停止契约。）
 */
class ChatTurnOperationRenewalScheduleTest {

    private ChatTurnOperationRepository repository;
    private ChatTurnOperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnOperationRepository.class);
        service = new ChatTurnOperationService(
                repository,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new RagChatProperties(),
                new RagProperties(),
                mock(ChatAuthorizationService.class),
                mock(ChatObservabilityService.class),
                mock(ChatExecutionService.class));
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

    @Test
    void unkeyedClaimNeverSchedulesRenewal() {
        ChatTurnOperationService.Claim claim =
                ChatTurnOperationService.Claim.unkeyed();

        service.startRenewal(claim);

        assertNull(claim.renewal());
        service.release(claim);
    }

    @Test
    void replayClaimNeverSchedulesRenewal() {
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(
                        runningOperation(), true);

        service.startRenewal(claim);

        assertNull(claim.renewal());
        service.release(claim);
    }

    @Test
    void keyedInProgressClaimSchedulesRenewalFuture() {
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(
                        runningOperation(), false);

        service.startRenewal(claim);

        assertNotNull(claim.renewal());
        assertFalse(claim.renewal().isDone());
        assertFalse(claim.renewalLost());
        service.release(claim);
    }

    @Test
    void repeatedStartRenewalDoesNotReschedule() {
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(
                        runningOperation(), false);

        service.startRenewal(claim);
        var firstFuture = claim.renewal();
        service.startRenewal(claim);

        // 已有续期任务时不重复调度。
        assertSame(firstFuture, claim.renewal());
        service.release(claim);
    }

    @Test
    void stopRenewalCancelsTheScheduledFuture() {
        ChatTurnOperationService.Claim claim =
                new ChatTurnOperationService.Claim(
                        runningOperation(), false);

        service.startRenewal(claim);
        var future = claim.renewal();
        service.release(claim);

        assertTrue(future.isCancelled());
        assertNull(claim.renewal());
    }

    @Test
    void stopRenewalToleratesNullClaims() {
        service.release(null);
        // release(null) 为显式 no-op：不抛异常即通过。
        assertTrue(true);
    }
}
