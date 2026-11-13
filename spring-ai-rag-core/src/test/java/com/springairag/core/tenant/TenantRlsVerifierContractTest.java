package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP3：verifier 语义契约的纯 JVM 部分（不依赖数据库）。
 *
 * <p>真实 PG 的五种负向启动用例见
 * {@code TenantRlsVerifierPostgresIntegrationTest}（opt-in）。</p>
 */
@DisplayName("TenantRlsVerifier 语义契约")
class TenantRlsVerifierContractTest {

    @Test
    @DisplayName("规范 policy 表达式必须绑定 GUC，拒绝 USING(true) / WITH CHECK(true)")
    void rejectsPermissivePolicyExpression() {
        assertTrue(TenantRlsVerifier.isPermissivePolicy("true"));
        assertTrue(TenantRlsVerifier.isPermissivePolicy("TRUE"));
        assertTrue(TenantRlsVerifier.isPermissivePolicy(" true "));
        assertFalse(TenantRlsVerifier.isPermissivePolicy(
                "tenant_id = current_setting('app.current_tenant_id', true)"));
    }

    @Test
    @DisplayName("catalog 中 TENANT_PRIVATE 表就是 RLS 覆盖清单")
    void rlsTableListComesFromCatalog() {
        List<String> tables = TenantRlsVerifier.rlsProtectedTables();
        assertFalse(tables.isEmpty());
        long privateCount = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.TENANT_PRIVATE)
                .count();
        assertTrue(tables.size() == privateCount,
                "RLS 清单必须与 catalog 的 TENANT_PRIVATE 裁决一致（不写死张数）");
    }
}
