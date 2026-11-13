package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP9：租户生命周期与状态的统一合同测试（先红后绿）。
 *
 * <p>参考 ../seedance-research/docs/multitenancy/drafts/用户自助注册B4三段式单事务化-实施方案.md
 * 的「幽灵租户」教训：租户行 + 初始 principal 必须单事务原子；并发创建只能有一个赢家；
 * SINGLE 模式拒绝创建非 default 租户。本仓按自包含对象模型验证，不引入外部私有协议。</p>
 */
@DisplayName("WP9 租户生命周期与状态")
class TenantWp9ContractTest {

    @Test
    @DisplayName("反模式：先写租户行再写 principal，principal 失败留下幽灵租户")
    void sequentialInsertLeavesGhostTenantAntiPattern() {
        var ghost = new GhostTenantCreator();
        ghost.insertTenant("acme");
        assertThrows(IllegalStateException.class, () -> ghost.insertInitialPrincipal("acme"),
                "模拟 principal 写入失败");
        assertTrue(ghost.tenantExists("acme"), "反模式应暴露：租户行已提交");
        assertFalse(ghost.principalExists("acme"), "反模式应暴露：无主租户");
    }

    @Test
    @DisplayName("推荐：创建走单事务，principal 失败时事务回滚（无幽灵租户）")
    void createIsAtomicWhenPrincipalInsertFails() {
        // 用可观测的 TransactionManager 替身，验证 Spring 事务边界真的触发 rollback；
        // 不靠 mock 的「看起来绿」。真实 DB 回滚由 TenantWp9PostgresIntegrationTest 覆盖。
        var tm = new RecordingTransactionManager();
        var service = new TenantLifecycleService(mock(JdbcTemplate.class),
                new TransactionTemplate(tm),
                TenantMode.STRICT, mock(TenantLifecycleAudit.class));
        var executor = new FailingPrincipalExecutor();
        assertThrows(IllegalStateException.class,
                () -> service.create(new TenantCreateCommand("acme", "Acme", "actor-1"), executor));
        assertTrue(tm.rollbackCalled, "事务必须回滚，否则留下幽灵租户");
        assertFalse(tm.commitCalled, "principal 失败时不得提交事务");
    }

    @Test
    @DisplayName("SINGLE 模式拒绝创建非 default 租户并记审计")
    void singleModeRejectsNonDefaultCreation() {
        var audit = mock(TenantLifecycleAudit.class);
        var service = new TenantLifecycleService(mock(JdbcTemplate.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                TenantMode.SINGLE, audit);
        var ex = assertThrows(IllegalStateException.class,
                () -> service.create(new TenantCreateCommand("acme", "Acme", "actor-1"),
                        new NoopTenantSqlExecutor()));
        assertTrue(ex.getMessage().contains("SINGLE"));
        org.mockito.Mockito.verify(audit).record(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("状态迁移：ACTIVE→SUSPENDED→ACTIVE / DISABLED 终态 / 软删幂等")
    void lifecycleTransitionsAndSoftDelete() {
        var service = new TenantLifecycleService(mock(JdbcTemplate.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                TenantMode.STRICT, mock(TenantLifecycleAudit.class));
        assertEquals(TenantStatus.ACTIVE, TenantStatus.from("ACTIVE"));
        assertEquals(TenantStatus.SUSPENDED, TenantStatus.from("SUSPENDED"));
        assertEquals(TenantStatus.DISABLED, TenantStatus.from("DISABLED"));
        assertTrue(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.SUSPENDED));
        assertFalse(TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.DISABLED));
        assertFalse(TenantStatus.DISABLED.canTransitionTo(TenantStatus.ACTIVE));
        assertTrue(service.softDeleteIsIdempotent());
    }

    @Test
    @DisplayName("非法 tenant_id 被 DB 正则契约拒绝（服务层前置校验）")
    void invalidTenantIdRejected() {
        var service = new TenantLifecycleService(mock(JdbcTemplate.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                TenantMode.STRICT, mock(TenantLifecycleAudit.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new TenantCreateCommand("Bad_ID!", "x", "actor"),
                        new NoopTenantSqlExecutor()));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new TenantCreateCommand("", "x", "actor"),
                        new NoopTenantSqlExecutor()));
    }

    // ------------------------------------------------------------------
    // 反模式/绿测替身（不触达真实 DB）
    // ------------------------------------------------------------------

    private static final class GhostTenantCreator {
        private final java.util.Set<String> tenants = new java.util.HashSet<>();
        private final java.util.Set<String> principals = new java.util.HashSet<>();

        void insertTenant(String id) { tenants.add(id); }
        void insertInitialPrincipal(String id) {
            throw new IllegalStateException("principal insert failed");
        }
        boolean tenantExists(String id) { return tenants.contains(id); }
        boolean principalExists(String id) { return principals.contains(id); }
    }

    private static final class FailingPrincipalExecutor implements TenantSqlExecutor {
        boolean tenantCommitted;
        @Override public void insertTenant(String tenantId, String displayName) {
            tenantCommitted = true;
        }
        @Override public void insertInitialPrincipal(String tenantId, String principalId) {
            throw new IllegalStateException("principal insert failed; transaction must roll back");
        }
        @Override public boolean tenantExists(String tenantId) { return false; }
        @Override public void updateStatus(String tenantId, String status) { }
        @Override public void markDeleted(String tenantId) { }
    }

    private static final class NoopTenantSqlExecutor implements TenantSqlExecutor {
        @Override public void insertTenant(String tenantId, String displayName) { }
        @Override public void insertInitialPrincipal(String tenantId, String principalId) { }
        @Override public boolean tenantExists(String tenantId) { return false; }
        @Override public void updateStatus(String tenantId, String status) { }
        @Override public void markDeleted(String tenantId) { }
    }

    private static final class RecordingTransactionManager
            implements PlatformTransactionManager {
        boolean commitCalled;
        boolean rollbackCalled;

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }
        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            commitCalled = true;
        }
        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            rollbackCalled = true;
        }
    }
}
