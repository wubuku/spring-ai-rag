package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP12：启动期端点声明覆盖契约（规划 §6 WP6 收口 / §7 Endpoint guard 行）。
 *
 * <p>{@code describe()} 快照（METHOD /pattern -> DECISION）解析为显式声明；
 * 平台 capability 端点按语义白名单显式裁决 PLATFORM_ONLY，租户内 MANAGEMENT
 * 端点落 TENANT_REQUIRED + COLLECTION_ACL；白名单漂移/误标 fail-closed。
 * 真实 Spring MVC 快照一致性由 {@code OpenApiContractTest} 共享上下文证明。</p>
 */
@DisplayName("TenantEndpointDeclarationCatalog (describe() 驱动的启动期全覆盖)")
class TenantEndpointDeclarationCatalogTest {

    private static final String PLATFORM_TENANTS = "GET /api/v1/rag/tenants -> MANAGEMENT";

    @Test
    @DisplayName("describe() 快照解析：默认租户内端点 + 平台白名单显式裁决")
    void parsesSnapshotIntoDeclarations() {
        var projection = TenantEndpointDeclarationCatalog.fromDescriptions(List.of(
                "GET /api/v1/rag/collections -> READ",
                "POST /api/v1/rag/documents -> WRITE",
                PLATFORM_TENANTS,
                "POST /v1/chat/completions -> WRITE"));
        assertEquals(Set.of("/api/v1/rag/collections", "/api/v1/rag/documents",
                        "/api/v1/rag/tenants", "/v1/chat/completions"),
                projection.actualPaths());
        assertEquals(4, projection.declarations().size());
        assertTrue(projection.declarations().stream()
                .anyMatch(d -> d.path().equals("/api/v1/rag/tenants")
                        && d.tenantScope() == TenantScope.PLATFORM_ONLY
                        && d.aclPolicy() == AclPolicy.PLATFORM_CAPABILITY));
        assertTrue(projection.declarations().stream()
                .anyMatch(d -> d.path().equals("/api/v1/rag/collections")
                        && d.tenantScope() == TenantScope.TENANT_REQUIRED
                        && d.aclPolicy() == AclPolicy.COLLECTION_ACL));
        projection.validate();
    }

    @Test
    @DisplayName("租户内 MANAGEMENT 端点（api-keys/alerts 等）落 TENANT_REQUIRED 基线")
    void tenantScopedManagementEndpointsUseBaseline() {
        var projection = TenantEndpointDeclarationCatalog.fromDescriptions(List.of(
                PLATFORM_TENANTS,
                "GET /api/v1/rag/api-keys -> MANAGEMENT",
                "POST /api/v1/rag/alerts/fire -> MANAGEMENT"));
        assertTrue(projection.declarations().stream()
                .anyMatch(d -> d.path().equals("/api/v1/rag/api-keys")
                        && d.tenantScope() == TenantScope.TENANT_REQUIRED
                        && d.aclPolicy() == AclPolicy.COLLECTION_ACL));
        projection.validate();
    }

    @Test
    @DisplayName("describe() 全部路径必须被 guard 接住（差集空）")
    void guardCoversAllActualPaths() {
        var projection = TenantEndpointDeclarationCatalog.fromDescriptions(List.of(
                PLATFORM_TENANTS,
                "GET /api/v1/rag/search -> READ",
                "POST /v1/embeddings -> WRITE"));
        assertEquals(projection.declarations().size(), projection.actualPaths().size());
        projection.validate();
    }

    @Test
    @DisplayName("新增端点漏声明 → guard 拒绝启动（红测）")
    void undeclaredEndpointFailsValidation() {
        var projection = TenantEndpointDeclarationCatalog.fromDescriptions(List.of(
                PLATFORM_TENANTS,
                "GET /api/v1/rag/collections -> READ"));
        var augmented = new TenantEndpointDeclarationCatalog.Projection(
                projection.declarations(),
                Set.of("/api/v1/rag/tenants", "/api/v1/rag/collections",
                        "/api/v1/rag/new-endpoint"),
                Set.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class, augmented::validate);
        assertTrue(ex.getMessage().contains("new-endpoint"));
    }

    @Test
    @DisplayName("平台端点条件装配缺席时不得误报（红测）")
    void absentConditionallyAssembledPlatformEndpointIsNotAnError() {
        // 平台端点由 @ConditionalOnBean 装配；无 TenantLifecycleService 的上下文里
        // 快照不含 /api/v1/rag/tenants，此时不得按「漏声明」误判。
        var projection = TenantEndpointDeclarationCatalog.fromDescriptions(List.of(
                "GET /api/v1/rag/collections -> READ"));
        projection.validate();
    }

    @Test
    @DisplayName("平台端点被误标为 READ → fail-closed（红测）")
    void invalidPlatformDecisionFailsClosed() {
        var projection = TenantEndpointDeclarationCatalog.fromDescriptions(List.of(
                "GET /api/v1/rag/tenants -> READ"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, projection::validate);
        assertTrue(ex.getMessage().contains("MANAGEMENT capability"));
    }

    @Test
    @DisplayName("describe() 格式畸形必须 fail-closed，而不是静默跳过")
    void malformedDescriptionFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> TenantEndpointDeclarationCatalog.fromDescriptions(
                        List.of("GET /api/v1/rag/collections")));
        assertThrows(IllegalStateException.class,
                () -> TenantEndpointDeclarationCatalog.fromDescriptions(
                        List.of("GET /relative/path -> READ")));
        assertThrows(IllegalStateException.class,
                () -> TenantEndpointDeclarationCatalog.fromDescriptions(
                        List.of("")));
    }
}
