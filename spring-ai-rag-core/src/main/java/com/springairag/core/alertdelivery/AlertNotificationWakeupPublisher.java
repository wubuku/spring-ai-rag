package com.springairag.core.alertdelivery;

import com.springairag.core.tenant.TenantContext;
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
                // WP7：注册时捕获租户上下文快照；afterCommit 在新连接/已清理上下文
                // 环境下执行，必须显式 bootstrap—assert—restore—clear
                //（参考项目 ../seedance-research/... after-commit 脏连接教训）。
                TransactionSynchronizationManager.registerSynchronization(
                        new WakeupSynchronization(TenantContext.current()));
            }
            return;
        }
        publishNow(TenantContext.current());
    }

    private void publishNow(TenantContext tenantSnapshot) {
        try {
            if (tenantSnapshot == null) {
                // 平台全局唤醒事件（如无租户上下文的运维动作）：不建立租户作用域。
                eventPublisher.publishEvent(new AlertNotificationsAvailableEvent());
                return;
            }
            TenantContext previous = TenantContext.current();
            try {
                TenantContext.set(tenantSnapshot);
                eventPublisher.publishEvent(new AlertNotificationsAvailableEvent());
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
        } catch (RuntimeException error) {
            log.warn("Failed to publish alert notification wake-up event");
        }
    }

    private final class WakeupSynchronization
            implements TransactionSynchronization {

        private final TenantContext tenantSnapshot;

        private WakeupSynchronization(TenantContext tenantSnapshot) {
            this.tenantSnapshot = tenantSnapshot;
        }

        @Override
        public void afterCommit() {
            publishNow(tenantSnapshot);
        }
    }
}
