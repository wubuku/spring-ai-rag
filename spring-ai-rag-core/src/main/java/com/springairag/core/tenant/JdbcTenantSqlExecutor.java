package com.springairag.core.tenant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WP9：{@link TenantSqlExecutor} 的 JDBC 实现（平台管理连接，凭据表无 RLS）。
 *
 * <p>所有写入都在 {@link TenantLifecycleService} 的事务边界内执行；
 * 「租户行 + 初始 principal」两步共用同一连接/事务，任一步异常整段回滚。</p>
 *
 * <p>初始 principal 的 INSERT 显式写入 {@code tenant_id}（V80 已给凭据表加该列），
 * 让 {@link PrincipalTenantResolver} 的 STRICT 解析能从认证投影里拿到真实绑定租户，
 * 不再依赖兼容构造器的 default 兜底。</p>
 */
public final class JdbcTenantSqlExecutor implements TenantSqlExecutor {

    private final JdbcTemplate jdbc;

    public JdbcTenantSqlExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertTenant(String tenantId, String displayName) {
        try {
            jdbc.update("INSERT INTO rag_tenant (tenant_id, display_name, status) "
                            + "VALUES (?, ?, 'ACTIVE')",
                    tenantId, displayName);
        } catch (DuplicateKeyException conflict) {
            throw new IllegalStateException("租户已存在：" + tenantId, conflict);
        }
    }

    @Override
    public void insertInitialPrincipal(String tenantId, String principalId) {
        // 初始 principal：最小可用记录（role=NORMAL、默认 capabilities）。
        // 真实 key 仍由 ApiKeyManagementService 后续轮换/签发；这里只建立绑定关系。
        jdbc.update("INSERT INTO rag_api_principal "
                        + "(principal_id, name, role, allowed_collection_ids, expires_at, "
                        + " requests_per_minute, capabilities, policy_version, "
                        + " next_credential_version, created_at, updated_at, tenant_id) "
                        + "VALUES (?, ?, 'NORMAL', NULL, NULL, NULL, "
                        + " 'RAG_READ,RAG_WRITE', 1, 2, CURRENT_TIMESTAMP, "
                        + " CURRENT_TIMESTAMP, ?)",
                principalId, "tenant-" + tenantId + "-bootstrap", tenantId);
    }

    @Override
    public boolean tenantExists(String tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM rag_tenant WHERE tenant_id = ?",
                Integer.class, tenantId);
        return count != null && count > 0;
    }

    @Override
    public void updateStatus(String tenantId, String status) {
        int updated = jdbc.update(
                "UPDATE rag_tenant SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE tenant_id = ? AND deleted_at IS NULL",
                status, tenantId);
        if (updated == 0) {
            throw new IllegalStateException("租户不存在或已软删：" + tenantId);
        }
    }

    @Override
    public void markDeleted(String tenantId) {
        jdbc.update("UPDATE rag_tenant SET deleted_at = CURRENT_TIMESTAMP, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE tenant_id = ? AND deleted_at IS NULL",
                tenantId);
    }
}
