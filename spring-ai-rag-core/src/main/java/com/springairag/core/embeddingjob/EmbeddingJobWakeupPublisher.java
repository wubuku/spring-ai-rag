package com.springairag.core.embeddingjob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在持久化任务事务提交后发布一次可合并的 worker 唤醒事件。
 */
@Component
public class EmbeddingJobWakeupPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(EmbeddingJobWakeupPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public EmbeddingJobWakeupPublisher(
            ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publishAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            boolean alreadyRegistered = TransactionSynchronizationManager
                    .getSynchronizations()
                    .stream()
                    .anyMatch(WakeupSynchronization.class::isInstance);
            if (!alreadyRegistered) {
                TransactionSynchronizationManager.registerSynchronization(
                        new WakeupSynchronization());
            }
            return;
        }
        publishNow();
    }

    private void publishNow() {
        try {
            eventPublisher.publishEvent(new EmbeddingJobsAvailableEvent());
        } catch (RuntimeException e) {
            // 数据库任务已经可靠落盘，低频恢复扫描会处理本次通知失败。
            log.warn("Failed to publish embedding job wake-up event", e);
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
