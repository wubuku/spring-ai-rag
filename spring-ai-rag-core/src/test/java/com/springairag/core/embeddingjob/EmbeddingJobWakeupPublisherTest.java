package com.springairag.core.embeddingjob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EmbeddingJobWakeupPublisherTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesOnceAfterCommitForManyJobsInOneTransaction() {
        ApplicationEventPublisher events =
                mock(ApplicationEventPublisher.class);
        EmbeddingJobWakeupPublisher publisher =
                new EmbeddingJobWakeupPublisher(events);
        beginTransaction();

        publisher.publishAfterCommit();
        publisher.publishAfterCommit();
        publisher.publishAfterCommit();

        verify(events, never()).publishEvent(any(Object.class));
        assertEquals(1,
                TransactionSynchronizationManager
                        .getSynchronizations().size());

        TransactionSynchronization synchronization =
                TransactionSynchronizationManager
                        .getSynchronizations().getFirst();
        synchronization.afterCommit();
        synchronization.afterCompletion(
                TransactionSynchronization.STATUS_COMMITTED);

        verify(events, times(1))
                .publishEvent(any(EmbeddingJobsAvailableEvent.class));
    }

    @Test
    void rollbackDoesNotPublishWakeUp() {
        ApplicationEventPublisher events =
                mock(ApplicationEventPublisher.class);
        EmbeddingJobWakeupPublisher publisher =
                new EmbeddingJobWakeupPublisher(events);
        beginTransaction();

        publisher.publishAfterCommit();
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager
                        .getSynchronizations().getFirst();
        synchronization.afterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void publishesImmediatelyWhenCallerHasNoTransaction() {
        ApplicationEventPublisher events =
                mock(ApplicationEventPublisher.class);
        EmbeddingJobWakeupPublisher publisher =
                new EmbeddingJobWakeupPublisher(events);

        publisher.publishAfterCommit();

        verify(events).publishEvent(
                any(EmbeddingJobsAvailableEvent.class));
    }

    @Test
    void listenerFailureCannotInvalidateDurableJobCommit() {
        ApplicationEventPublisher events =
                mock(ApplicationEventPublisher.class);
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("listener unavailable"))
                .when(events)
                .publishEvent(any(Object.class));
        EmbeddingJobWakeupPublisher publisher =
                new EmbeddingJobWakeupPublisher(events);

        publisher.publishAfterCommit();

        verify(events).publishEvent(
                any(EmbeddingJobsAvailableEvent.class));
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
