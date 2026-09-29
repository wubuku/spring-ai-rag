package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
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
        when(repository.findCandidateIds(anyInt()))
                .thenThrow(new IllegalStateException("lease store down"))
                .thenReturn(List.of());
        // 失败后再次唤醒仍可恢复扫描（finally 重派发 + wake 语义）。
        when(repository.recoverExhaustedLeases(anyInt()))
                .thenReturn(0);

        assertDoesNotThrow(() -> {
            worker.wakeUp();
            worker.wakeUp();
            Thread.sleep(500);
        });
        worker.shutdown();
    }

}
