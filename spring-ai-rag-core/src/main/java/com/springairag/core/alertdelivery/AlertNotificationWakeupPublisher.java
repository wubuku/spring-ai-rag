package com.springairag.core.alertdelivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在 delivery 事务提交后发布一次可合并的 worker 唤醒事件。 */
@Component
public class AlertNotificationWakeupPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(AlertNotificationWakeupPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public AlertNotificationWakeupPublisher(
            ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publishAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            boolean registered = TransactionSynchronizationManager
                    .getSynchronizations()
                    .stream()
                    .anyMatch(WakeupSynchronization.class::isInstance);
            if (!registered) {
                TransactionSynchronizationManager.registerSynchronization(
                        new WakeupSynchronization());
            }
            return;
        }
        publishNow();
    }

    private void publishNow() {
        try {
            eventPublisher.publishEvent(new AlertNotificationsAvailableEvent());
        } catch (RuntimeException error) {
            log.warn("Failed to publish alert notification wake-up event");
        }
    }

    private final class WakeupSynchronization
            implements TransactionSynchronization {
        @Override
        public void afterCommit() {
            publishNow();
        }
    }
}
