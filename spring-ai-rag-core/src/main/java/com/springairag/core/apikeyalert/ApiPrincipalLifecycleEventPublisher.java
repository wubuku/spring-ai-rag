package com.springairag.core.apikeyalert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在 API principal 写事务提交后发布可合并的生命周期提示。
 */
@Component
public class ApiPrincipalLifecycleEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(
            ApiPrincipalLifecycleEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public ApiPrincipalLifecycleEventPublisher(
            ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publishAfterCommit(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            boolean alreadyRegistered = TransactionSynchronizationManager
                    .getSynchronizations()
                    .stream()
                    .filter(PrincipalSynchronization.class::isInstance)
                    .map(PrincipalSynchronization.class::cast)
                    .anyMatch(sync -> sync.principalId.equals(principalId));
            if (!alreadyRegistered) {
                TransactionSynchronizationManager.registerSynchronization(
                        new PrincipalSynchronization(principalId));
            }
            return;
        }
        publishNow(principalId);
    }

    private void publishNow(String principalId) {
        try {
            eventPublisher.publishEvent(
                    new ApiPrincipalLifecycleChangedEvent(principalId));
        } catch (RuntimeException error) {
            log.warn(
                    "Failed to publish API principal lifecycle event: principalId={}",
                    principalId,
                    error);
        }
    }

    private final class PrincipalSynchronization
            implements TransactionSynchronization {

        private final String principalId;

        private PrincipalSynchronization(String principalId) {
            this.principalId = principalId;
        }

        @Override
        public void afterCommit() {
            publishNow(principalId);
        }
    }
}
