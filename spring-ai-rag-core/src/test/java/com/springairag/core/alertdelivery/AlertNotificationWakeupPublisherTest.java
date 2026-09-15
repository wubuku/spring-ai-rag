package com.springairag.core.alertdelivery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * AlertNotificationWakeupPublisher（Batch 415）：无事务立即发布、
 * 事务内注册去重并延迟到 afterCommit、发布失败被吞掉。
 */
class AlertNotificationWakeupPublisherTest {

    private ApplicationEventPublisher eventPublisher;
    private AlertNotificationWakeupPublisher publisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        publisher = new AlertNotificationWakeupPublisher(eventPublisher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesImmediatelyOutsideTransaction() {
        publisher.publishAfterCommit();

        ArgumentCaptor<AlertNotificationsAvailableEvent> event =
                ArgumentCaptor.forClass(AlertNotificationsAvailableEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(AlertNotificationsAvailableEvent.class,
                event.getValue().getClass());
    }

    @Test
    void defersPublicationUntilAfterCommitAndDeduplicates() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit();
        publisher.publishAfterCommit();

        // 事务内不发布；重复调用只注册一次（合并语义）。
        verify(eventPublisher, never()).publishEvent(any());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.getFirst().afterCommit();

        verify(eventPublisher, times(1)).publishEvent(
                any(AlertNotificationsAvailableEvent.class));
    }

    @Test
    void publisherFailureIsSwallowedWithoutAffectingCaller() {
        doThrow(new IllegalStateException("no listeners"))
                .when(eventPublisher).publishEvent(any());

        assertDoesNotThrow(publisher::publishAfterCommit);
    }
}
