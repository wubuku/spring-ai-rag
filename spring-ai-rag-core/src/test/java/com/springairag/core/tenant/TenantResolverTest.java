package com.springairag.core.tenant;

import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5：租户解析 SPI（实施规划 §5.3.2 / §6 WP5）。
 *
 * <p>契约：</p>
 * <ol>
 *   <li>解析顺序：宿主可信 resolver（本版不提供）→ 凭据绑定 tenant_id → 单租户 default；</li>
 *   <li>禁止从不受信 header 推导租户（本版 SPI 不接收 header）；</li>
 *   <li>凭据解析必须校验凭据 tenant 与 principal 状态一致；</li>
 *   <li>解析失败（无绑定 / 租户不存在或非 ACTIVE）返回 empty 或抛出；
 *       不静默落回 default，避免越权路径。</li>
 * </ol>
 */
@DisplayName("TenantResolver SPI (宿主/凭据/单租户三段)")
class TenantResolverTest {

    private static AuthenticatedApiPrincipal principal(String tenantId, String status) {
        return new AuthenticatedApiPrincipal("principal-1", "cred-1", 1,
                ApiKeyAuthFilterBridge.PRINCIPAL_DATABASE_API_KEY, ApiKeyRole.NORMAL,
                null, null, 1L, 10, List.of("RAG_READ"),
                tenantId, status);
    }

    /** 桥接常量，避免测试依赖 web 包。 */
    private static final class ApiKeyAuthFilterBridge {
        static final String PRINCIPAL_DATABASE_API_KEY = "DATABASE_API_KEY";
    }

    @Test
    @DisplayName("SingleTenantResolver 固定解析 default，忽略 principal")
    void singleResolverPinsDefault() {
        SingleTenantResolver resolver = new SingleTenantResolver();
        assertEquals(Optional.of("default"), resolver.resolveTenantId(principal("other", "ACTIVE")));
        assertEquals(Optional.of("default"), resolver.resolveTenantId(null));
    }

    @Test
    @DisplayName("PrincipalTenantResolver 返回凭据绑定租户")
    void principalResolverUsesCredentialTenant() {
        PrincipalTenantResolver resolver = new PrincipalTenantResolver();
        assertEquals(Optional.of("tenant-a"),
                resolver.resolveTenantId(principal("tenant-a", "ACTIVE")));
    }

    @Test
    @DisplayName("凭据未绑定租户 → 解析失败（不落回 default）")
    void unboundCredentialFailsClosed() {
        PrincipalTenantResolver resolver = new PrincipalTenantResolver();
        Optional<String> result = resolver.resolveTenantId(principal(null, "ACTIVE"));
        assertTrue(result.isEmpty(), "凭据 tenant 为 null 时必须 fail-closed");
    }

    @Test
    @DisplayName("租户状态非 ACTIVE → 拒绝（SUSPENDED/DISABLED 均不可解析）")
    void suspendedTenantRejected() {
        PrincipalTenantResolver resolver = new PrincipalTenantResolver();
        assertTrue(resolver.resolveTenantId(principal("tenant-a", "SUSPENDED")).isEmpty());
        assertTrue(resolver.resolveTenantId(principal("tenant-a", "DISABLED")).isEmpty());
    }

    @Test
    @DisplayName("PrincipalTenantResolver 拒绝 null principal")
    void nullPrincipalRejected() {
        PrincipalTenantResolver resolver = new PrincipalTenantResolver();
        assertTrue(resolver.resolveTenantId(null).isEmpty());
    }
}
