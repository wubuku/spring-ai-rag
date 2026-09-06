package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.NotificationConfig;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 operator 投递查询与人工重试：枚举校验、游标分页、状态机守卫、
 * provider 可用性、managed 状态过期与重试唤醒。
 */
class AlertNotificationDeliveryServiceTest {

    private AlertNotificationDeliveryRepository repository;
    private AlertNotificationOutboxService outboxService;
    private AlertNotificationWakeupPublisher wakeupPublisher;
    private AlertNotificationProvider provider;
    private AlertNotificationDeliveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlertNotificationDeliveryRepository.class);
        outboxService = mock(AlertNotificationOutboxService.class);
        wakeupPublisher = mock(AlertNotificationWakeupPublisher.class);
        provider = mock(AlertNotificationProvider.class);
        NotificationConfig config = new NotificationConfig();
        config.getDelivery().setEnabled(true);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new AlertNotificationDeliveryService(
                repository, outboxService, wakeupPublisher, config,
                transactionManager, new ObjectMapper().findAndRegisterModules());
        when(outboxService.configuredProviders()).thenReturn(List.of("DINGTALK"));
    }

    private AlertNotificationDeliveryRecord record(String status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AlertNotificationDeliveryRecord(
                UUID.randomUUID(), 42L, 3, true, "DINGTALK", status,
                mock(AlertNotificationPayload.class), 2, 8, 0,
                now, null, null, null, null, null, null, now, now);
    }

    @Test
    void queryRejectsInvalidStatusAndProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> service.query("BOGUS", null, null, 20, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.query(null, "BOGUS", null, 20, null));
    }

    @Test
    void queryNormalizesEnumsAndReturnsFirstPage() {
        when(repository.query(eq("PENDING"), isNull(), isNull(),
                isNull(), isNull(), eq(3)))
                .thenReturn(List.of(record("PENDING"), record("PENDING")));

        var page = service.query("pending", null, null, 2, null);

        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        assertEquals(2, page.items().size());
        assertEquals(List.of("DINGTALK"), page.configuredProviders());
        assertTrue(page.durableDeliveryEnabled());
    }

    @Test
    void queryBuildsNextCursorWhenMoreRowsExist() {
        List<AlertNotificationDeliveryRecord> rows = List.of(
                record("PENDING"), record("PENDING"), record("PENDING"));
        when(repository.query(eq("PENDING"), isNull(), isNull(),
                isNull(), isNull(), eq(3)))
                .thenReturn(rows);

        var page = service.query("PENDING", null, null, 2, null);

        assertTrue(page.hasMore());
        assertEquals(2, page.items().size());
        assertTrue(page.nextCursor() != null && !page.nextCursor().isBlank());
    }

    @Test
    void queryDecodesCursorIntoPositionForRepository() {
        AlertNotificationCursorCodec codec = new AlertNotificationCursorCodec(
                new ObjectMapper().findAndRegisterModules());
        OffsetDateTime cursorAt = OffsetDateTime.parse("2026-09-01T10:15:30Z");
        UUID lastId = UUID.randomUUID();
        String cursor = codec.encode("PENDING", "DINGTALK", 42L, cursorAt, lastId);
        when(repository.query(eq("PENDING"), eq("DINGTALK"), eq(42L),
                eq(cursorAt), eq(lastId), eq(3)))
                .thenReturn(List.of(record("PENDING")));

        var page = service.query("PENDING", "DINGTALK", 42L, 2, cursor);

        assertEquals(1, page.items().size());
        assertFalse(page.hasMore());
    }

    @Test
    void retryReturnsInFlightRecordWithoutProviderLookup() {
        AlertNotificationDeliveryRecord pending = record("PENDING");
        when(repository.find(pending.id())).thenReturn(Optional.of(pending));

        var response = service.retry(pending.id());

        assertEquals(pending.id(), response.id());
        assertEquals("PENDING", response.status());
        verify(outboxService, never()).provider(any());
    }

    @Test
    void retryThrowsConflictForTerminalDeliveredRecord() {
        AlertNotificationDeliveryRecord delivered = record("DELIVERED");
        when(repository.find(delivered.id())).thenReturn(Optional.of(delivered));

        RagException error = assertThrows(RagException.class,
                () -> service.retry(delivered.id()));
        assertEquals(ErrorCode.ALERT_NOTIFICATION_DELIVERY_CONFLICT,
                error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("cannot be retried"));
    }

    @Test
    void retryThrowsNotFoundForMissingDelivery() {
        when(repository.find(any(UUID.class))).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.retry(UUID.randomUUID()));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void retryRejectsWhenProviderIsUnavailable() {
        AlertNotificationDeliveryRecord failed = record("FAILED");
        when(repository.find(failed.id())).thenReturn(Optional.of(failed));
        when(outboxService.provider("DINGTALK")).thenReturn(null);

        RagException error = assertThrows(RagException.class,
                () -> service.retry(failed.id()));
        assertTrue(error.getMessage().contains("not currently available"));
    }

    @Test
    void retrySupersedesFailedManagedDeliveryWithStaleState() {
        AlertNotificationDeliveryRecord failed = record("FAILED");
        AlertNotificationDeliveryRecord superseded = record("SUPERSEDED");
        when(repository.find(failed.id()))
                .thenReturn(Optional.of(failed))
                .thenReturn(Optional.of(superseded));
        when(outboxService.provider("DINGTALK")).thenReturn(provider);
        when(provider.isRoutedFor(any())).thenReturn(true);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(repository.isManagedStateCurrent(42L, 3)).thenReturn(false);
        when(repository.markFailedAsSuperseded(failed.id())).thenReturn(true);

        RagException error = assertThrows(RagException.class,
                () -> service.retry(failed.id()));

        assertTrue(error.getMessage().contains("no longer current"));
        verify(repository).markFailedAsSuperseded(failed.id());
        verify(wakeupPublisher, never()).publishAfterCommit();
    }

    @Test
    void retryRequeuesFailedDeliveryAndWakesWorker() {
        AlertNotificationDeliveryRecord failed = record("FAILED");
        AlertNotificationDeliveryRecord requeued = record("RETRY_WAIT");
        when(repository.find(failed.id())).thenReturn(Optional.of(failed));
        when(outboxService.provider("DINGTALK")).thenReturn(provider);
        when(provider.isRoutedFor(any())).thenReturn(true);
        when(provider.isCurrentlyAvailable()).thenReturn(true);
        when(repository.isManagedStateCurrent(42L, 3)).thenReturn(true);
        when(repository.retryFailed(failed.id(), 8))
                .thenReturn(Optional.of(requeued));

        var response = service.retry(failed.id());

        assertEquals("RETRY_WAIT", response.status());
        verify(wakeupPublisher).publishAfterCommit();
    }
}
