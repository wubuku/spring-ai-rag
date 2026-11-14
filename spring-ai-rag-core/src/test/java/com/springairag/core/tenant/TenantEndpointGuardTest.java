package com.springairag.core.tenant;

import com.springairag.core.security.ApiRoutePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP6：端点 guard 覆盖测试（实施规划 §6 WP6 / §7）。
 *
 * <p>契约：所有写/读端点必须声明 tenant + ACL 策略；新端点漏声明即红。
 * 这里用注册表快照驱动纯 JVM 测试，不依赖 Spring MVC 上下文。</p>
 */
@DisplayName("TenantEndpointGuard (端点 tenant + ACL 声明覆盖)")
class TenantEndpointGuardTest {

    @Test
    @DisplayName("全部声明（READ + TENANT_REQUIRED + ACL）→ 通过")
    void allDeclaredPasses() {
        TenantEndpointGuard guard = new TenantEndpointGuard(Set.of(
                new EndpointDeclaration("/api/v1/rag/search", ApiRoutePolicy.Access.READ,
                        TenantScope.TENANT_REQUIRED, AclPolicy.COLLECTION_ACL),
                new EndpointDeclaration("/api/v1/rag/documents", ApiRoutePolicy.Access.WRITE,
                        TenantScope.TENANT_REQUIRED, AclPolicy.COLLECTION_ACL)));
        guard.validate(Set.of("/api/v1/rag/search", "/api/v1/rag/documents"));
    }

    @Test
    @DisplayName("新增端点漏声明 → 红")
    void undeclaredEndpointIsRejected() {
        TenantEndpointGuard guard = new TenantEndpointGuard(Set.of(
                new EndpointDeclaration("/api/v1/rag/search", ApiRoutePolicy.Access.READ,
                        TenantScope.TENANT_REQUIRED, AclPolicy.COLLECTION_ACL)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.validate(Set.of("/api/v1/rag/search", "/api/v1/rag/new-endpoint")));
        assertTrue(ex.getMessage().contains("new-endpoint"));
    }

    @Test
    @DisplayName("声明了 capability 但缺 tenant 策略 → 红")
    void missingTenantScopeRejected() {
        TenantEndpointGuard guard = new TenantEndpointGuard(Set.of(
                new EndpointDeclaration("/api/v1/rag/search", ApiRoutePolicy.Access.READ,
                        null, AclPolicy.COLLECTION_ACL)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.validate(Set.of("/api/v1/rag/search")));
        assertTrue(ex.getMessage().contains("TENANT"));
    }

    @Test
    @DisplayName("声明了 tenant 但缺 ACL 策略 → 红")
    void missingAclPolicyRejected() {
        TenantEndpointGuard guard = new TenantEndpointGuard(Set.of(
                new EndpointDeclaration("/api/v1/rag/search", ApiRoutePolicy.Access.READ,
                        TenantScope.TENANT_REQUIRED, null)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.validate(Set.of("/api/v1/rag/search")));
        assertTrue(ex.getMessage().contains("ACL"));
    }

    @Test
    @DisplayName("平台 capability 端点必须显式声明 PLATFORM_ONLY")
    void platformEndpointRequiresExplicitScope() {
        TenantEndpointGuard guard = new TenantEndpointGuard(Set.of(
                new EndpointDeclaration("/api/v1/platform/tenants",
                        ApiRoutePolicy.Access.MANAGEMENT,
                        TenantScope.TENANT_REQUIRED, AclPolicy.COLLECTION_ACL)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.validate(Set.of("/api/v1/platform/tenants")));
        assertTrue(ex.getMessage().contains("PLATFORM_ONLY"));
    }
}
