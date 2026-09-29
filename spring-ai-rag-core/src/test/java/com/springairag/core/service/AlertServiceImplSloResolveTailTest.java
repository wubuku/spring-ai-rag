package com.springairag.core.service;

import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.entity.RagAlert;
import com.springairag.core.repository.AlertRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertServiceImpl 长尾补充（Batch 704，JaCoCo 驱动）：SLO_BREACH
 * 对 latency/time 类指标的 > 比较与未达标不告警、resolveAlert 在
 * outbox 与 conditionState 齐备时取代托管通知、静默清理的调度入
 * 口委托。
 */
class AlertServiceImplSloResolveTailTest {

    private AlertRepository alertRepository;
    private AlertNotificationOutboxService outboxService;
    private AlertServiceImpl alertService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        outboxService = mock(AlertNotificationOutboxService.class);
        alertService = new AlertServiceImpl(
                alertRepository,
                mock(RagRetrievalLogRepository.class),
                mock(RagRetrievalEvaluationRepository.class),
                mock(RagSilenceScheduleRepository.class),
                List.of(),
                new com.springairag.core.config.RagAlertProperties(),
                outboxService);
    }

    @Test
    void sloBreachLatencyBelowThresholdDoesNotAlert() {
        assertFalse(alertService.shouldAlert(
                "SLO_BREACH", "latency_p95", 1000, 2000));
    }

    @Test
    void sloBreachTimeMetricUsesGreaterComparison() {
        assertTrue(alertService.shouldAlert(
                "SLO_BREACH", "elapsed_time", 3000, 2000));
        assertFalse(alertService.shouldAlert(
                "SLO_BREACH", "elapsed_time", 1000, 2000));
    }

    @Test
    void resolveAlertSupersedesManagedOutboxWhenConditionStatePresent() {
        RagAlert alert = new RagAlert();
        alert.setId(7L);
        alert.setStatus("ACTIVE");
        alert.setConditionState("{\"state_version\": 3}");
        when(alertRepository.findById(7L)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(RagAlert.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        alertService.resolveAlert(7L, "Fixed");

        assertEqualsResolved(alert);
        verify(outboxService).supersedeManaged(7L);
    }

    private void assertEqualsResolved(RagAlert alert) {
        assertTrue("RESOLVED".equals(alert.getStatus()));
    }

    @Test
    void scheduledSilenceCleanupDelegatesWithoutError() {
        // 空仓储下调度入口应无异常完成委托。
        alertService.scheduledSilenceCleanup();
        Mockito.verifyNoInteractions(outboxService);
    }
}
