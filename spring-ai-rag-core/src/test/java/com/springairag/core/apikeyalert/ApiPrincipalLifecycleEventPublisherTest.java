package com.springairag.core.apikeyalert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApiPrincipalLifecycleEventPublisherTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesImmediatelyWithoutTransaction() {
        ApplicationEventPublisher delegate =
                mock(ApplicationEventPublisher.class);
        ApiPrincipalLifecycleEventPublisher publisher =
                new ApiPrincipalLifecycleEventPublisher(delegate);

        publisher.publishAfterCommit("principal-1");

        verify(delegate).publishEvent(
                new ApiPrincipalLifecycleChangedEvent("principal-1"));
    }

    @Test
    void publishesOnceAfterCommitAndNotBefore() {
        ApplicationEventPublisher delegate =
                mock(ApplicationEventPublisher.class);
        ApiPrincipalLifecycleEventPublisher publisher =
                new ApiPrincipalLifecycleEventPublisher(delegate);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit("principal-1");
        publisher.publishAfterCommit("principal-1");

        verify(delegate, never()).publishEvent(
                new ApiPrincipalLifecycleChangedEvent("principal-1"));
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());

        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(delegate).publishEvent(
                new ApiPrincipalLifecycleChangedEvent("principal-1"));
    }

    @Test
    void rollbackDoesNotPublish() {
        ApplicationEventPublisher delegate =
                mock(ApplicationEventPublisher.class);
        ApiPrincipalLifecycleEventPublisher publisher =
                new ApiPrincipalLifecycleEventPublisher(delegate);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit("principal-1");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(delegate, never()).publishEvent(
                new ApiPrincipalLifecycleChangedEvent("principal-1"));
    }
}
