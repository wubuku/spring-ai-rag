package com.springairag.core.tenant;

import com.springairag.core.alertdelivery.AlertNotificationWakeupPublisher;
import com.springairag.core.apikeyalert.ApiPrincipalLifecycleEventPublisher;
import com.springairag.core.embeddingjob.EmbeddingJobWakeupPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * WP7：after-commit 回调上下文传播合同（实施规划 §5.4 / §6 WP7）。
 *
 * <p>参考项目 ../seedance-research/... checklist §G「after-commit 脏连接」教训：
 * 回调运行在事务提交后，连接/GUC 可能已清理；若回调触发新连接读租户私有表，
 * 无租户上下文会读到 0 行或串台。合同要求注册时捕获上下文，
 * 回调里显式 bootstrap—assert—restore—clear。</p>
 */
@DisplayName("WP7 after-commit 回调租户上下文传播")
class TenantAfterCommitContextTest {

    @AfterEach
    void clearTx() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    @Test
    @DisplayName("AlertNotificationWakeupPublisher：提交前捕获 tenant，回调里可见并清理")
    void alertPublisherPropagatesTenantIntoAfterCommit() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AlertNotificationWakeupPublisher publisher =
                new AlertNotificationWakeupPublisher(events);
        beginTransaction();
        TenantContext.set(new TenantContext(
                "tenant-a", "principal-1", TenantMode.STRICT, "audit-1"));

        publisher.publishAfterCommit();
        TenantContext.clear(); // 模拟事务提交后连接/上下文已清理

        AtomicReference<String> seen = new AtomicReference<>("not-run");
        List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        // 事件发布本身会被 mock 吞掉；这里用 publisher 的回调执行期观察上下文。
        // 通过给 events 装 Answer 捕获回调执行时的 TenantContext。
        org.mockito.Mockito.doAnswer(invocation -> {
            seen.set(TenantContext.currentTenantId());
            return null;
        }).when(events).publishEvent(any(Object.class));

        syncs.getFirst().afterCommit();

        assertEquals("tenant-a", seen.get(), "afterCommit 必须仍能看到注册时的租户上下文");
        assertNull(TenantContext.current(), "回调结束后上下文必须清理干净");
    }

    @Test
    @DisplayName("EmbeddingJobWakeupPublisher：提交前捕获 tenant，回调里可见并清理")
    void embeddingPublisherPropagatesTenantIntoAfterCommit() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        EmbeddingJobWakeupPublisher publisher =
                new EmbeddingJobWakeupPublisher(events);
        beginTransaction();
        TenantContext.set(new TenantContext(
                "tenant-b", "principal-2", TenantMode.STRICT, "audit-2"));
        publisher.publishAfterCommit();
        TenantContext.clear();

        AtomicReference<String> seen = new AtomicReference<>("not-run");
        org.mockito.Mockito.doAnswer(invocation -> {
            seen.set(TenantContext.currentTenantId());
            return null;
        }).when(events).publishEvent(any(Object.class));

        TransactionSynchronizationManager.getSynchronizations()
                .getFirst().afterCommit();

        assertEquals("tenant-b", seen.get());
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("ApiPrincipalLifecycleEventPublisher：提交前捕获 tenant，回调里可见并清理")
    void principalPublisherPropagatesTenantIntoAfterCommit() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ApiPrincipalLifecycleEventPublisher publisher =
                new ApiPrincipalLifecycleEventPublisher(events);
        beginTransaction();
        TenantContext.set(new TenantContext(
                "tenant-c", "principal-3", TenantMode.STRICT, "audit-3"));
        publisher.publishAfterCommit("principal-3");
        TenantContext.clear();

        AtomicReference<String> seen = new AtomicReference<>("not-run");
        org.mockito.Mockito.doAnswer(invocation -> {
            seen.set(TenantContext.currentTenantId());
            return null;
        }).when(events).publishEvent(any(Object.class));

        TransactionSynchronizationManager.getSynchronizations()
                .getFirst().afterCommit();

        assertEquals("tenant-c", seen.get());
        assertNull(TenantContext.current());
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
