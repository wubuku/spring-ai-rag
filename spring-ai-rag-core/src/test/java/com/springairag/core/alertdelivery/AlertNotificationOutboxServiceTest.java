package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import com.springairag.core.entity.RagAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖告警事务 outbox：durable 开关门、按路由分发到 provider、
 * 插入计数与唤醒、managed 版本收敛以及 provider 查找。
 */
class AlertNotificationOutboxServiceTest {

    private AlertNotificationDeliveryRepository repository;
    private AlertNotificationPayloadSanitizer sanitizer;
    private AlertNotificationWakeupPublisher wakeupPublisher;
    private AlertNotificationProvider webhookProvider;
    private AlertNotificationProvider emailProvider;
    private AlertNotificationOutboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlertNotificationDeliveryRepository.class);
        sanitizer = mock(AlertNotificationPayloadSanitizer.class);
        wakeupPublisher = mock(AlertNotificationWakeupPublisher.class);
        webhookProvider = provider("webhook", true);
        emailProvider = provider("email", true);
        NotificationConfig config = new NotificationConfig();
        config.getDelivery().setEnabled(true);
        config.getDelivery().setMaxAttempts(8);
        service = new AlertNotificationOutboxService(
                repository,
                sanitizer,
                wakeupPublisher,
                config,
                List.of(webhookProvider, emailProvider));
        when(sanitizer.create(any(UUID.class), anyString(), anyString(),
                anyString(), anyString(), any()))
                .thenReturn(new AlertNotificationPayloadSanitizer.SanitizedPayload(
                        null, "{}"));
        when(repository.insert(any(UUID.class), any(long.class), any(int.class),
                any(boolean.class), anyString(), anyString(), any(int.class)))
                .thenReturn(true);
    }

    private AlertNotificationProvider provider(String name, boolean routed) {
        AlertNotificationProvider provider = mock(AlertNotificationProvider.class);
        when(provider.provider()).thenReturn(name);
        when(provider.isRoutedFor(anyString())).thenReturn(routed);
        return provider;
    }

    private RagAlert alert() {
        RagAlert alert = new RagAlert();
        alert.setId(42L);
        alert.setAlertType("THRESHOLD_HIGH");
        alert.setAlertName("Latency breach");
        alert.setSeverity("CRITICAL");
        alert.setMessage("p95 above target");
        alert.setMetrics(Map.of("p95", 900));
        return alert;
    }

    @Test
    void enqueueOrdinaryReturnsZeroWhenDurableDisabled() {
        service = disabledService();

        assertEquals(0, service.enqueueOrdinary(alert()));
        verify(repository, never()).insert(any(), any(long.class), any(int.class),
                any(boolean.class), anyString(), anyString(), any(int.class));
    }

    @Test
    void enqueuesToEveryRoutedProviderAndWakesTheWorker() {
        int inserted = service.enqueueOrdinary(alert());

        assertEquals(2, inserted);
        verify(repository, org.mockito.Mockito.times(2)).insert(
                any(UUID.class), eq(42L), eq(1), eq(false),
                anyString(), eq("{}"), eq(8));
        verify(wakeupPublisher).publishAfterCommit();
    }

    @Test
    void skipsProvidersWithoutMatchingRoute() {
        when(webhookProvider.isRoutedFor("THRESHOLD_HIGH")).thenReturn(false);

        int inserted = service.enqueueOrdinary(alert());

        assertEquals(1, inserted);
        verify(repository).insert(any(UUID.class), eq(42L), eq(1), eq(false),
                eq("email"), eq("{}"), eq(8));
    }

    @Test
    void countsOnlySuccessfulInsertsAndStillWakesWhenAtLeastOne() {
        when(repository.insert(any(UUID.class), any(long.class), any(int.class),
                any(boolean.class), eq("webhook"), anyString(), any(int.class)))
                .thenReturn(false);

        int inserted = service.enqueueOrdinary(alert());

        assertEquals(1, inserted);
        verify(wakeupPublisher).publishAfterCommit();
    }

    @Test
    void skipsWakeupWhenNoProviderAcceptedTheInsert() {
        when(repository.insert(any(UUID.class), any(long.class), any(int.class),
                any(boolean.class), anyString(), anyString(), any(int.class)))
                .thenReturn(false);

        assertEquals(0, service.enqueueOrdinary(alert()));
        verify(wakeupPublisher, never()).publishAfterCommit();
    }

    @Test
    void enqueueManagedSupersedesOlderVersionsBeforeInserting() {
        int inserted = service.enqueueManaged(42L, 3, "THRESHOLD_HIGH",
                "Latency breach", "CRITICAL", "message", Map.of());

        assertEquals(2, inserted);
        verify(repository).supersedeOlderManaged(42L, 3);
        verify(repository, org.mockito.Mockito.times(2)).insert(
                any(UUID.class), eq(42L), eq(3), eq(true),
                anyString(), eq("{}"), eq(8));
    }

    @Test
    void enqueueManagedSkipsSupersedeAndInsertWhenDurableDisabled() {
        service = disabledService();

        assertEquals(0, service.enqueueManaged(42L, 3, "THRESHOLD_HIGH",
                "Latency breach", "CRITICAL", "message", Map.of()));
        verify(repository, never()).supersedeOlderManaged(42L, 3);
    }

    @Test
    void supersedeManagedDelegatesOnlyWhenEnabled() {
        service.supersedeManaged(42L);
        verify(repository).supersedeManaged(42L);

        service = disabledService();
        service.supersedeManaged(42L);
        verify(repository, org.mockito.Mockito.times(1)).supersedeManaged(42L);
    }

    @Test
    void providerLookupReturnsNullForUnknownName() {
        assertNull(service.provider("missing"));
        assertEquals(webhookProvider, service.provider("webhook"));
    }

    private AlertNotificationOutboxService disabledService() {
        NotificationConfig config = new NotificationConfig();
        config.getDelivery().setEnabled(false);
        return new AlertNotificationOutboxService(
                repository,
                sanitizer,
                wakeupPublisher,
                config,
                List.of(webhookProvider, emailProvider));
    }
}
