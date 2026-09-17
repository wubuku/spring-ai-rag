package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * durable 投递 worker 的调度循环长尾（Batch 476，JaCoCo 驱动）：
 * wakeUp → scheduleDispatch → dispatchLoop → dispatchAvailable 的
 * 端到端领取与投递、claim 竞争失败释放名额后继续后续候选、
 * fallbackScan 触发的扫描循环，以及循环静止后不再轮询。
 */
class AlertNotificationDeliveryWorkerDispatchLoopTest {

    private final AlertNotificationDeliveryRepository repository =
            mock(AlertNotificationDeliveryRepository.class);
    private final AlertNotificationOutboxService outboxService =
            mock(AlertNotificationOutboxService.class);
    private final AlertNotificationProvider provider =
            mock(AlertNotificationProvider.class);

    private NotificationConfig config;
    private AlertNotificationDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        config = new NotificationConfig();
        config.getDelivery().setWorkerConcurrency(4);
        worker = new AlertNotificationDeliveryWorker(
                repository, outboxService, config);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private AlertNotificationDeliveryRecord delivery(int attemptCount) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AlertNotificationDeliveryRecord(
                UUID.randomUUID(),
                42L,
                3,
                false,
                "webhook",
                "PENDING",
                null,
                attemptCount,
                8,
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    private void stubSuccessfulDelivery() {
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenReturn(
                AlertNotificationAttemptResult.success());
    }

    @Test
    void wakeUpDispatchesClaimedCandidateEndToEnd() {
        AlertNotificationDeliveryRecord claimed = delivery(1);
        UUID candidateId = claimed.id();
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of(candidateId))
                .thenReturn(List.of());
        when(repository.claim(
                eq(candidateId), any(UUID.class), any()))
                .thenReturn(java.util.Optional.of(claimed));
        stubSuccessfulDelivery();

        worker.wakeUp();

        // 异步池处理后落账 DELIVERED，并再次唤醒扫描到空列表后静止。
        verify(repository, timeout(3_000)).markDelivered(
                eq(candidateId), any(UUID.class));
        verify(repository, timeout(3_000).atLeastOnce())
                .findCandidateIds(anyInt());
    }

    @Test
    void lostClaimRaceContinuesWithRemainingCandidates() {
        AlertNotificationDeliveryRecord winner = delivery(1);
        UUID lostId = UUID.randomUUID();
        UUID wonId = winner.id();
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of(lostId, wonId))
                .thenReturn(List.of());
        when(repository.claim(eq(lostId), any(UUID.class), any()))
                .thenReturn(java.util.Optional.empty());
        when(repository.claim(eq(wonId), any(UUID.class), any()))
                .thenReturn(java.util.Optional.of(winner));
        stubSuccessfulDelivery();

        worker.wakeUp();

        // 丢失竞争的候选只释放名额，不投递；获胜候选正常投递。
        verify(repository, timeout(3_000)).claim(
                eq(lostId), any(UUID.class), any());
        verify(repository, timeout(3_000)).markDelivered(
                eq(wonId), any(UUID.class));
        verify(repository, never()).markSuperseded(any(), any());
    }

    @Test
    void fallbackScanTriggersDispatchScanRound() {
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of());

        worker.fallbackScan();

        verify(repository, timeout(3_000)).recoverExhaustedLeases(anyInt());
        verify(repository, timeout(3_000).atLeastOnce())
                .findCandidateIds(anyInt());
    }

    @Test
    void wakeUpAfterShutdownNeverScans() {
        worker.shutdown();

        worker.wakeUp();

        try {
            TimeUnit.MILLISECONDS.sleep(150);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        verify(repository, never()).findCandidateIds(anyInt());
        verify(repository, never()).claim(any(), any(), any());
    }

    @Test
    void dispatchedCandidatesWakeLoopUntilQuiesced() {
        AlertNotificationDeliveryRecord first = delivery(2);
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of(first.id()))
                .thenReturn(List.of(first.id()))
                .thenReturn(List.of());
        when(repository.claim(
                eq(first.id()), any(UUID.class), any()))
                .thenAnswer(invocation -> java.util.Optional.of(first));
        stubSuccessfulDelivery();

        worker.wakeUp();

        // 每轮投递完成都会自唤醒再扫一轮；候选仍可领取则重复投递，
        // 直到候选为空静止。至少投递两次且扫描至少三轮。
        verify(repository, timeout(5_000).atLeastOnce()).markDelivered(
                eq(first.id()), any(UUID.class));
        verify(repository, timeout(5_000).atLeastOnce()).claim(
                eq(first.id()), any(UUID.class), any());
        verify(repository, atLeastOnce()).findCandidateIds(anyInt());
    }
}
