package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WP6：平台 capability 注册表（实施规划 §4.6 / §6 WP6）。
 *
 * <p>契约：跨租户 discovery 只返回最小身份投影；命名查询枚举 + 白名单 + 审计；
 * 未登记的 capability 直接拒绝（负向测试）；业务包不得依赖平台池实现。</p>
 */
@DisplayName("PlatformCapabilityRegistry (最小投影/白名单/审计)")
class PlatformCapabilityRegistryTest {

    @Test
    @DisplayName("登记 capability 可返回最小投影（tenantId + status）")
    void registeredCapabilityReturnsMinimalProjection() {
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
        registry.allowActor("admin-1");
        registry.register(PlatformCapability.TENANT_DISCOVERY,
                principalId -> new TenantProjection("tenant-a", "ACTIVE"));
        TenantProjection p = registry.execute(
                PlatformCapability.TENANT_DISCOVERY, "admin-1", "tenant-a");
        assertEquals("tenant-a", p.tenantId());
        assertEquals("ACTIVE", p.status());
    }

    @Test
    @DisplayName("未登记 capability → 拒绝（不隐式放行）")
    void unregisteredCapabilityRejected() {
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
        assertThrows(IllegalStateException.class,
                () -> registry.execute(PlatformCapability.TENANT_DISCOVERY, "admin-1", "tenant-a"));
    }

    @Test
    @DisplayName("白名单外 actor → 拒绝并记审计")
    void actorOutsideWhitelistRejected() {
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
        registry.allowActor("platform-admin");
        registry.register(PlatformCapability.TENANT_DISCOVERY,
                principalId -> new TenantProjection("tenant-a", "ACTIVE"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.execute(PlatformCapability.TENANT_DISCOVERY, "rogue", "tenant-a"));
        assertFalse(registry.auditEvents().isEmpty());
    }

    @Test
    @DisplayName("每次调用都记审计：capability + actor + target，不含私有正文")
    void everyCallIsAudited() {
        PlatformCapabilityRegistry registry = new PlatformCapabilityRegistry();
        registry.allowActor("platform-admin");
        registry.register(PlatformCapability.TENANT_DISCOVERY,
                principalId -> new TenantProjection("tenant-a", "ACTIVE"));
        registry.execute(PlatformCapability.TENANT_DISCOVERY, "platform-admin", "tenant-a");
        assertEquals(1, registry.auditEvents().size());
        assertTrueAudit(registry.auditEvents().get(0));
    }

    private static void assertTrueAudit(PlatformAuditEvent event) {
        org.junit.jupiter.api.Assertions.assertTrue(
                event.capability() == PlatformCapability.TENANT_DISCOVERY
                        && "platform-admin".equals(event.actorId())
                        && "tenant-a".equals(event.targetTenantId()));
    }
}
