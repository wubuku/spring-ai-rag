package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * WP9：凭据认证投影必须带出 tenant_id 与租户状态（WP5 遗留收口）。
 *
 * <p>规划 §5.2：凭据表无 RLS，认证先于租户解析；STRICT 模式下
 * {@link PrincipalTenantResolver} 只能靠认证投影里的绑定值解析租户，
 * 不能用兼容构造器的 default 兜底冒充真实绑定。</p>
 */
@DisplayName("WP9 凭据绑定投影")
class TenantCredentialBindingProjectionTest {

    @Test
    @DisplayName("AuthenticationProjection 暴露 tenantId 与 tenantStatus")
    void authenticationProjectionExposesTenantBinding() {
        // 用动态代理构造投影，断言接口方法存在且返回绑定值；
        // 真实 SQL 投影由 TenantWp9PostgresIntegrationTest 验证。
        var projection = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{com.springairag.core.repository.RagApiKeyRepository.AuthenticationProjection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTenantId" -> "acme";
                    case "getTenantStatus" -> "ACTIVE";
                    case "getPrincipalId" -> "p-1";
                    case "getRole" -> com.springairag.core.entity.ApiKeyRole.NORMAL;
                    case "getCredentialVersion" -> 1;
                    case "getPolicyVersion" -> 1L;
                    case "getCapabilities" -> "RAG_READ,RAG_WRITE";
                    default -> null;
                });
        var typed = (com.springairag.core.repository.RagApiKeyRepository.AuthenticationProjection) projection;
        assertNotNull(typed);
        assertEquals("acme", typed.getTenantId());
        assertEquals("ACTIVE", typed.getTenantStatus());
    }

    @Test
    @DisplayName("PrincipalTenantResolver 对非 ACTIVE 绑定租户 fail-closed")
    void resolverRejectsInactiveTenant() {
        var resolver = new PrincipalTenantResolver();
        var principal = new com.springairag.core.security.AuthenticatedApiPrincipal(
                "p", "k", 1, "DB", com.springairag.core.entity.ApiKeyRole.NORMAL,
                null, null, 1L, null, java.util.List.of("RAG_READ"),
                "acme", "SUSPENDED");
        assertEquals(java.util.Optional.empty(), resolver.resolveTenantId(principal));
    }
}
