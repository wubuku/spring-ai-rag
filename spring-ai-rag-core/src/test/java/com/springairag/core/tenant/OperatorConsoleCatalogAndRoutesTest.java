package com.springairag.core.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2：运营方管理控制台表目录与路由声明单元测试。
 */
class OperatorConsoleCatalogAndRoutesTest {

    @Test
    void v86PlatformTablesAreRegisteredAsPlatformGlobalWithoutRls() {
        TenantTableCatalog.TableEntry audit =
                TenantTableCatalog.requireRegistered("rag_platform_audit_event");
        TenantTableCatalog.TableEntry support =
                TenantTableCatalog.requireRegistered("rag_operator_support_session");

        assertEquals(TenantTableCatalog.TableOwnership.PLATFORM_GLOBAL, audit.ownership());
        assertFalse(audit.rlsEnabled());
        assertEquals(TenantTableCatalog.TableOwnership.PLATFORM_GLOBAL, support.ownership());
        assertFalse(support.rlsEnabled());
    }

    @Test
    void operatorConsoleRoutesAreClassifiedAsPlatformOnlyCapability() {
        List<String> descriptions = List.of(
                "GET /api/v1/rag/operator/tenants -> MANAGEMENT",
                "POST /api/v1/rag/operator/tenants -> MANAGEMENT",
                "GET /api/v1/rag/operator/credentials -> MANAGEMENT",
                "PUT /api/v1/rag/operator/tenants/{tenantId}/quotas/{period} -> MANAGEMENT",
                "GET /api/v1/rag/operator/compliance -> MANAGEMENT",
                "GET /api/v1/rag/operator/audit-events -> MANAGEMENT",
                "GET /api/v1/rag/collections -> READ");

        TenantEndpointDeclarationCatalog.Projection projection =
                TenantEndpointDeclarationCatalog.fromDescriptions(descriptions);

        assertDoesNotThrow(projection::validate);
        // 同一路径的不同 HTTP method 会产生同一条声明（declarations 按 path 去重）。
        long platformCount = projection.declarations().stream()
                .filter(d -> d.tenantScope() == TenantScope.PLATFORM_ONLY
                        && d.aclPolicy() == AclPolicy.PLATFORM_CAPABILITY)
                .count();
        assertEquals(5, platformCount);
    }

    @Test
    void operatorConsoleRouteWithNonManagementAccessFailsValidation() {
        List<String> invalid = List.of("GET /api/v1/rag/operator/tenants -> READ");
        TenantEndpointDeclarationCatalog.Projection projection =
                TenantEndpointDeclarationCatalog.fromDescriptions(invalid);

        assertThrows(IllegalStateException.class, projection::validate);
    }
}
