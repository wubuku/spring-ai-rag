package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertNotificationDeliveryWorker 调度长尾（Batch 519，JaCoCo 驱
 * 动）：事件入口、dispatchLoop 对仓储故障的吞并、executor 拒绝时
 * 的名额回收与 CAS 复位、名额耗尽跳过扫描、指数退避溢出封顶、
 * shutdown 对阻塞投递的 shutdownNow。
 */
class AlertNotificationDeliveryWorkerScheduleTailTest {

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
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
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

    private ExecutorService reflectedExecutor() throws Exception {
        var field = AlertNotificationDeliveryWorker.class
                .getDeclaredField("executor");
        field.setAccessible(true);
        return (ExecutorService) field.get(worker);
    }

    private Semaphore reflectedSlots() throws Exception {
        var field = AlertNotificationDeliveryWorker.class
                .getDeclaredField("slots");
        field.setAccessible(true);
        return (Semaphore) field.get(worker);
    }

    private void invokeDispatchAvailable() throws Exception {
        var method = AlertNotificationDeliveryWorker.class
                .getDeclaredMethod("dispatchAvailable");
        method.setAccessible(true);
        method.invoke(worker);
    }

    @Test
    void availableEventTriggersDispatchScan() {
        AlertNotificationDeliveryRecord claimed = delivery(1);
        UUID candidateId = claimed.id();
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of(candidateId))
                .thenReturn(List.of());
        when(repository.claim(eq(candidateId), any(UUID.class), any()))
                .thenReturn(Optional.of(claimed));
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenReturn(
                AlertNotificationAttemptResult.success());

        worker.onAvailable(new AlertNotificationsAvailableEvent());

        verify(repository, timeout(3_000)).markDelivered(
                eq(candidateId), any(UUID.class));
    }

    @Test
    void dispatchLoopSwallowsRepositoryFailure() {
        when(repository.findCandidateIds(anyInt()))
                .thenThrow(new IllegalStateException("repository down"));

        worker.wakeUp();

        // 循环捕获运行时异常后不再重试，也不会投递任何通知。
        verify(repository, timeout(3_000)).findCandidateIds(anyInt());
        verify(repository, never()).markDelivered(any(), any());
    }

    @Test
    void scheduleDispatchResetsFlagWhenExecutorRejects() throws Exception {
        // 只关 executor，不置 shuttingDown，模拟关闭竞态中的唤醒。
        reflectedExecutor().shutdown();

        worker.wakeUp();
        worker.wakeUp();

        // 拒绝路径复位 dispatchScheduled，允许后续唤醒继续尝试。
        reflectedExecutor().awaitTermination(1, TimeUnit.SECONDS);
        verify(repository, never()).findCandidateIds(anyInt());
    }

    @Test
    void dispatchAvailableReleasesSlotWhenSubmissionRejected()
            throws Exception {
        reflectedExecutor().shutdown();
        AlertNotificationDeliveryRecord claimed = delivery(1);
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of(claimed.id()));
        when(repository.claim(eq(claimed.id()), any(UUID.class), any()))
                .thenReturn(Optional.of(claimed));

        invokeDispatchAvailable();

        // 提交被拒：领取用的名额必须归还，避免名额泄漏。
        assertEquals(4, reflectedSlots().availablePermits());
        verify(repository).claim(eq(claimed.id()), any(UUID.class), any());
        verify(repository, never()).markDelivered(any(), any());
    }

    @Test
    void dispatchAvailableSkipsScanWhenSlotsExhausted() throws Exception {
        reflectedSlots().acquire(4);

        invokeDispatchAvailable();

        verify(repository, never()).findCandidateIds(anyInt());
    }

    @Test
    void transientRetryDelayCapsOnBackoffOverflow() {
        config.getDelivery().setInitialBackoff(
                Duration.ofMillis(Long.MAX_VALUE / 2));
        config.getDelivery().setMaxBackoff(Duration.ofSeconds(60));
        AlertNotificationDeliveryRecord claimed = delivery(20);
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenReturn(
                AlertNotificationAttemptResult.transientFailure(
                        "TRANSIENT_RATE_LIMIT", 429, null));

        worker.process(claimed, UUID.randomUUID());

        ArgumentCaptor<Duration> delay =
                ArgumentCaptor.forClass(Duration.class);
        verify(repository).markTransientFailure(
                eq(claimed), any(UUID.class), eq("TRANSIENT_RATE_LIMIT"),
                eq(429), delay.capture());
        // 溢出走 Long.MAX_VALUE 后被 maxBackoff(60s) 封顶，抖动 ±20%。
        long maxBackoffMs = 60_000;
        assertTrue(delay.getValue().toMillis() >= maxBackoffMs - 12_000);
        assertTrue(delay.getValue().toMillis() <= maxBackoffMs + 12_000);
    }

    @Test
    void shutdownNowInterruptsBlockedDelivery() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AlertNotificationDeliveryRecord claimed = delivery(1);
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of(claimed.id()))
                .thenReturn(List.of());
        when(repository.claim(eq(claimed.id()), any(UUID.class), any()))
                .thenReturn(Optional.of(claimed));
        when(outboxService.provider("webhook")).thenReturn(provider);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(provider.deliver(any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(15, TimeUnit.SECONDS);
            return AlertNotificationAttemptResult.success();
        });

        worker.wakeUp();
        assertTrue(started.await(5, TimeUnit.SECONDS),
                "provider deliver 应已开始执行");

        // 阻塞任务令 awaitTermination 超时，shutdown 经 shutdownNow 中断后返回。
        long start = System.currentTimeMillis();
        worker.shutdown();
        assertTrue(System.currentTimeMillis() - start < 30_000);
        release.countDown();
    }
}
