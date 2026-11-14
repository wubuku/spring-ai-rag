package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6：租户成员/状态守卫（实施规划 §5.3.6 / §6 WP6）。
 *
 * <p>契约：无上下文、SUSPENDED、DISABLED、跨租户成员不匹配一律拒绝。</p>
 */
@DisplayName("TenantMembershipGuard (状态/成员校验)")
class TenantMembershipGuardTest {

    private final TenantMembershipGuard guard = new TenantMembershipGuard();

    @Test
    @DisplayName("ACTIVE + 同租户 → 通过")
    void activeMatchingTenantPasses() {
        guard.verify(new TenantContext("tenant-a", "principal-1", TenantMode.STRICT),
                "tenant-a", "ACTIVE");
    }

    @Test
    @DisplayName("无上下文 → fail-closed 拒绝")
    void noContextRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verify(null, "tenant-a", "ACTIVE"));
        assertTrue(ex.getMessage().contains("上下文"));
    }

    @Test
    @DisplayName("SUSPENDED / DISABLED → 拒绝")
    void inactiveTenantRejected() {
        TenantContext c = new TenantContext("tenant-a", "principal-1", TenantMode.STRICT);
        assertThrows(IllegalStateException.class,
                () -> guard.verify(c, "tenant-a", "SUSPENDED"));
        assertThrows(IllegalStateException.class,
                () -> guard.verify(c, "tenant-a", "DISABLED"));
    }

    @Test
    @DisplayName("工作项租户与上下文不一致 → 拒绝（防串台）")
    void mismatchedTenantRejected() {
        TenantContext c = new TenantContext("tenant-a", "principal-1", TenantMode.STRICT);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.verify(c, "tenant-b", "ACTIVE"));
        assertTrue(ex.getMessage().contains("tenant-a"));
    }
}
