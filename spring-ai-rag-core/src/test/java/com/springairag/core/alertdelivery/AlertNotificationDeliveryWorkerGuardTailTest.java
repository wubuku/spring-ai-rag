package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知投递 worker 调度/清理长尾（Batch 730，JaCoCo 驱动）：
 * dispatchLoop 吞掉派发扫描的运行时异常避免调度线程死亡
 * （116-119）、cleanup 定时入口委托仓储清理（236-238）。
 */
class AlertNotificationDeliveryWorkerGuardTailTest {

    private AlertNotificationDeliveryRepository repository;
    private NotificationConfig config;

    @BeforeEach
    void setUp() {
        repository = mock(AlertNotificationDeliveryRepository.class);
        config = new NotificationConfig();
        config.getDelivery().setWorkerConcurrency(2);
    }

    @Test
    void cleanupDelegatesToRepositoryRetentions() {
        AlertNotificationDeliveryWorker worker =
                new AlertNotificationDeliveryWorker(
                        repository,
                        mock(AlertNotificationOutboxService.class),
                        config);

        worker.cleanup();

        var delivery = config.getDelivery();
        verify(repository).cleanup(
                delivery.getDeliveredRetention(),
                delivery.getFailedRetention(),
                delivery.getCleanupBatchSize());
    }

    @Test
    void fallbackScanRecoversExhaustedLeasesBeforeWake() {
        AlertNotificationDeliveryWorker worker =
                new AlertNotificationDeliveryWorker(
                        repository,
                        mock(AlertNotificationOutboxService.class),
                        config);
        when(repository.findCandidateIds(anyInt()))
                .thenReturn(List.of());

        worker.fallbackScan();

        verify(repository).recoverExhaustedLeases(
                config.getDelivery().getClaimBatchSize());
        worker.shutdown();
    }

    @Test
    void dispatchLoopSurvivesRepositoryRuntimeFailures() throws Exception {
        AlertNotificationDeliveryWorker worker =
                new AlertNotificationDeliveryWorker(
                        repository,
                        mock(AlertNotificationOutboxService.class),
                        config);
        AtomicInteger scans = new AtomicInteger();
        CountDownLatch firstScan = new CountDownLatch(1);
        when(repository.findCandidateIds(anyInt())).thenAnswer(invocation -> {
            if (scans.incrementAndGet() == 1) {
                firstScan.countDown();
                throw new IllegalStateException("lease store down");
            }
            return List.of();
        });

        worker.wakeUp();
        try {
            // 原来这里是 `assertDoesNotThrow(wakeUp ×2 + sleep(500))`，两处都不
            // 成立：wakeUp 内部把 RejectedExecutionException 吃掉了，外面根本
            // 抛不出东西；而 500ms 睡眠是在赌"扫描线程已经跑过一轮"，机器一慢
            // 就变成纯等待。更糟的是这条用例的注释声称覆盖"失败后再次唤醒仍可
            // 恢复扫描"，但没有任何断言去验恢复真的发生过——不恢复它照样是绿的。
            //
            // 现在把断言落在事实本身：第一次扫描真的发生并真的抛了（latch），
            // 第二次扫描真的发生（Mockito 有界等待，不赌时序）。
            assertTrue(firstScan.await(5, TimeUnit.SECONDS),
                    "第一次扫描没有发生：dispatchLoop 根本没被派发");

            // 失败后再唤醒一次。dispatchLoop 的 catch 吞掉异常后，finally 见到
            // wakeRequested 为真就会重派发；两种落点（wakeUp 早于/晚于 finally
            // 的 dispatchScheduled 归零）都会导致第二次扫描，所以这个断言是
            // 确定性的，不依赖线程调度。
            worker.wakeUp();

            verify(repository, timeout(5_000).atLeast(2)).findCandidateIds(anyInt());
        } finally {
            worker.shutdown();
        }
    }

}
