package com.springairag.core.service;

import com.springairag.core.alertdelivery.AlertNotificationOutboxService;
import com.springairag.core.config.RagAlertProperties;
import com.springairag.core.entity.RagAlert;
import com.springairag.core.entity.RagSilenceSchedule;
import com.springairag.core.repository.AlertRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.repository.RagSilenceScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertServiceImpl 静默与 SLO 长尾（Batch 489，JaCoCo 驱动）：
 * durable outbox 优先投递、legacy 通道异常吞噬、未知 SLO 判定、
 * 零请求可用性满分、ONE_TIME 静默窗口命中/解析失败/通配/仓储异常
 * 的四类静默判定。
 */
class AlertServiceImplFireSilenceSloTailTest {

    @Mock
    private AlertRepository alertRepository;
    @Mock
    private RagRetrievalLogRepository retrievalLogRepository;
    @Mock
    private RagRetrievalEvaluationRepository evaluationRepository;
    @Mock
    private RagSilenceScheduleRepository silenceScheduleRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AlertNotificationOutboxService outboxService;

    private RagAlertProperties alertProperties;

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        retrievalLogRepository = mock(RagRetrievalLogRepository.class);
        evaluationRepository = mock(RagRetrievalEvaluationRepository.class);
        silenceScheduleRepository = mock(RagSilenceScheduleRepository.class);
        notificationService = mock(NotificationService.class);
        outboxService = mock(AlertNotificationOutboxService.class);
        alertProperties = new RagAlertProperties();
        when(alertRepository.saveAndFlush(any(RagAlert.class)))
                .thenAnswer(invocation -> {
                    RagAlert alert = invocation.getArgument(0);
                    if (alert.getId() == null) {
                        alert.setId(11L);
                    }
                    return alert;
                });
    }

    private AlertServiceImpl service(AlertNotificationOutboxService outbox) {
        return new AlertServiceImpl(
                alertRepository,
                retrievalLogRepository,
                evaluationRepository,
                silenceScheduleRepository,
                List.of(notificationService),
                alertProperties,
                outbox);
    }

    private RagSilenceSchedule schedule(String type, String start, String end) {
        RagSilenceSchedule schedule = new RagSilenceSchedule();
        schedule.setAlertKey("THRESHOLD_HIGH:latency");
        schedule.setSilenceType(type);
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setEnabled(true);
        return schedule;
    }

    @Test
    void durableOutboxEnqueuesAndSkipsLegacyChannels() {
        when(outboxService.isDurableEnabled()).thenReturn(true);
        AlertServiceImpl service = service(outboxService);

        Long id = service.fireAlert("THRESHOLD_HIGH", "latency",
                "latency too high", "warning", Map.of());

        assertNotNull(id);
        verify(outboxService).enqueueOrdinary(any(RagAlert.class));
        verify(notificationService, never()).sendAlert(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void legacyChannelFailureIsSwallowed() {
        AlertServiceImpl service = service(null);
        when(notificationService.sendAlert(anyString(), anyString(),
                anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("channel down"));

        Long id = service.fireAlert("THRESHOLD_HIGH", "latency",
                "latency too high", "warning", Map.of());

        assertNotNull(id);
        verify(notificationService).sendAlert(eq("THRESHOLD_HIGH"),
                eq("latency"), eq("warning"), eq("latency too high"),
                any());
    }

    @Test
    void unknownSloNameIsUnmet() {
        AlertServiceImpl service = service(null);

        var status = service.checkSlo("bogus", ZonedDateTime.now(), ZonedDateTime.now());

        assertEquals("bogus", status.getSloName());
        assertFalse(status.isMet());
    }

    @Test
    void availabilitySloWithZeroRequestsIsPerfect() {
        AlertServiceImpl service = service(null);
        when(retrievalLogRepository.countByCreatedAtBetween(any(), any()))
                .thenReturn(0L);

        var status = service.checkSlo("availability",
                ZonedDateTime.now().minusHours(1), ZonedDateTime.now());

        assertEquals(100.0, status.getActual());
        assertTrue(status.isMet());
    }

    @Test
    void activeOneTimeSilenceWindowSuppressesSilenceCheck() {
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(
                eq("THRESHOLD_HIGH:latency"))).thenReturn(List.of());
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(
                isNull())).thenReturn(List.of(schedule(
                "ONE_TIME",
                ZonedDateTime.now().minusHours(1).toString(),
                ZonedDateTime.now().plusHours(1).toString())));
        AlertServiceImpl service = service(null);

        assertTrue(service.isSilenced("THRESHOLD_HIGH", "latency"));
    }

    @Test
    void unparseableSilenceWindowIsNotSilenced() {
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(
                eq("THRESHOLD_HIGH:latency"))).thenReturn(List.of(schedule(
                "ONE_TIME", "not-a-time", "also-not-a-time")));
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(
                isNull())).thenReturn(List.of());
        AlertServiceImpl service = service(null);

        assertFalse(service.isSilenced("THRESHOLD_HIGH", "latency"));
    }

    @Test
    void silenceRepositoryFailureIsNotSilenced() {
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(anyString()))
                .thenThrow(new IllegalStateException("db down"));
        AlertServiceImpl service = service(null);

        assertFalse(service.isSilenced("THRESHOLD_HIGH", "latency"));
    }

    @Test
    void silencedScheduleSkipsFireAlertEntirely() {
        when(silenceScheduleRepository.findByAlertKeyAndEnabledTrue(
                eq("THRESHOLD_HIGH:latency"))).thenReturn(List.of(schedule(
                "ONE_TIME",
                ZonedDateTime.now().minusHours(1).toString(),
                ZonedDateTime.now().plusHours(1).toString())));
        AlertServiceImpl service = service(null);

        Long id = service.fireAlert("THRESHOLD_HIGH", "latency",
                "should not fire", "warning", Map.of());

        assertNull(id);
        verify(alertRepository, never()).saveAndFlush(any());
    }
}
