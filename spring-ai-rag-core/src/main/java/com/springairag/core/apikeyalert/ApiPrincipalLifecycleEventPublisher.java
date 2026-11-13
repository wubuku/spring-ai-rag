package com.springairag.core.apikeyalert;

import com.springairag.core.tenant.TenantContext;
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
                // WP7：凭据表无 RLS，但生命周期回调可能触发租户私有逻辑，
                // 仍必须在注册时捕获上下文并在 afterCommit 显式传播。
                TransactionSynchronizationManager.registerSynchronization(
                        new PrincipalSynchronization(
                                principalId, TenantContext.current()));
            }
            return;
        }
        publishNow(principalId, TenantContext.current());
    }

    private void publishNow(String principalId, TenantContext tenantSnapshot) {
        try {
            if (tenantSnapshot == null) {
                eventPublisher.publishEvent(
                        new ApiPrincipalLifecycleChangedEvent(principalId));
                return;
            }
            TenantContext previous = TenantContext.current();
            try {
                TenantContext.set(tenantSnapshot);
                eventPublisher.publishEvent(
                        new ApiPrincipalLifecycleChangedEvent(principalId));
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
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
        private final TenantContext tenantSnapshot;

        private PrincipalSynchronization(
                String principalId, TenantContext tenantSnapshot) {
            this.principalId = principalId;
            this.tenantSnapshot = tenantSnapshot;
        }

        @Override
        public void afterCommit() {
            publishNow(principalId, tenantSnapshot);
        }
    }
}
