package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 durable 投递 worker 的单条处理决策矩阵与调度入口：
 * 托管状态过期跳过、provider 缺失/不可用、三种投递结果落点、
 * provider 异常的瞬态降级以及 lease 恢复与清理配置。
 */
class AlertNotificationDeliveryWorkerTest {

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
        config.getDelivery().setWorkerConcurrency(1);
        worker = new AlertNotificationDeliveryWorker(
                repository, outboxService, config);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    void supersedesWhenManagedStateIsStale() {
        AlertNotificationDeliveryRecord delivery =
                delivery(true, 1);
        UUID leaseToken = UUID.randomUUID();
        when(repository.isManagedStateCurrent(42L, 3)).thenReturn(false);

        worker.process(delivery, leaseToken);

        verify(repository).markSuperseded(delivery.id(), leaseToken);
        verify(outboxService, never()).provider(any());
        verify(repository, never()).markDelivered(any(), any());
    }

    @Test
    void deliversWhenManagedStateIsCurrent() {
        AlertNotificationDeliveryRecord delivery =
                delivery(true, 1);
        UUID leaseToken = UUID.randomUUID();
        when(repository.isManagedStateCurrent(42L, 3)).thenReturn(true);
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenReturn(
                AlertNotificationAttemptResult.success());

        worker.process(delivery, leaseToken);

        verify(repository).markDelivered(delivery.id(), leaseToken);
    }

    @Test
    void permanentFailureWhenProviderIsUnknown() {
        AlertNotificationDeliveryRecord delivery =
                delivery(false, 1);
        UUID leaseToken = UUID.randomUUID();
        when(outboxService.provider("webhook")).thenReturn(null);

        worker.process(delivery, leaseToken);

        verify(repository).markPermanentFailure(
                delivery.id(), leaseToken, "PERMANENT_CONFIGURATION", null);
    }

    @Test
    void permanentFailureWhenProviderIsNotAvailable() {
        AlertNotificationDeliveryRecord delivery =
                delivery(false, 1);
        UUID leaseToken = UUID.randomUUID();
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(false);

        worker.process(delivery, leaseToken);

        verify(repository).markPermanentFailure(
                delivery.id(), leaseToken, "PERMANENT_CONFIGURATION", null);
        verify(provider, never()).deliver(any());
    }

    @Test
    void permanentOutcomeMarksPermanentFailureWithDetails() {
        AlertNotificationDeliveryRecord delivery =
                delivery(false, 2);
        UUID leaseToken = UUID.randomUUID();
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenReturn(
                AlertNotificationAttemptResult.permanentFailure("BAD_ROUTE", 400));

        worker.process(delivery, leaseToken);

        verify(repository).markPermanentFailure(
                delivery.id(), leaseToken, "BAD_ROUTE", 400);
    }

    @Test
    void transientOutcomeMarksTransientFailureHonoringRetryAfter() {
        AlertNotificationDeliveryRecord delivery =
                delivery(false, 2);
        UUID leaseToken = UUID.randomUUID();
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenReturn(
                AlertNotificationAttemptResult.transientFailure(
                        "TRANSIENT_HTTP", 503, Duration.ofSeconds(7)));

        worker.process(delivery, leaseToken);

        verify(repository).markTransientFailure(
                eq(delivery), eq(leaseToken), eq("TRANSIENT_HTTP"), eq(503),
                delayCaptor());
    }

    @Test
    void providerExceptionDegradesToTransientNetworkFailure() {
        AlertNotificationDeliveryRecord delivery =
                delivery(false, 3);
        UUID leaseToken = UUID.randomUUID();
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any()))
                .thenThrow(new IllegalStateException("connection reset"));

        worker.process(delivery, leaseToken);

        verify(repository).markTransientFailure(
                eq(delivery), eq(leaseToken), eq("TRANSIENT_NETWORK"), eq(null),
                delayCaptor());
    }

    @Test
    void fallbackScanRecoversExhaustedLeases() {
        worker.fallbackScan();

        verify(repository).recoverExhaustedLeases(
                config.getDelivery().getClaimBatchSize());
    }

    @Test
    void cleanupAppliesRetentionConfiguration() {
        worker.cleanup();

        verify(repository).cleanup(
                config.getDelivery().getDeliveredRetention(),
                config.getDelivery().getFailedRetention(),
                config.getDelivery().getCleanupBatchSize());
    }

    @Test
    void wakeUpAfterShutdownDoesNotDispatch() throws Exception {
        worker.shutdown();

        worker.wakeUp();

        // 给异步 dispatch 循环留出（不应发生的）执行窗口
        TimeUnit.MILLISECONDS.sleep(100);
        verify(repository, never()).findCandidateIds(anyInt());
    }

    private Duration delayCaptor() {
        return org.mockito.ArgumentMatchers.argThat(delay -> delay != null
                && delay.toMillis() >= 7_000
                && delay.toMillis() <= 4_320_000);
    }

    private AlertNotificationDeliveryRecord delivery(
            boolean managedCondition, int attemptCount) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AlertNotificationDeliveryRecord(
                UUID.randomUUID(),
                42L,
                3,
                managedCondition,
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
}
