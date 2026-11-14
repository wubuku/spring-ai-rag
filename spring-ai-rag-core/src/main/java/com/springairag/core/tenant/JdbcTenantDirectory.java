package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * WP7：{@link TenantDirectory} 的 JDBC 实现（实施规划 §5.4「多租户用平台池枚举 ACTIVE 租户」）。
 *
 * <p>{@code rag_tenant} 是 PLATFORM_GLOBAL 平台表（无 RLS），用平台/管理连接读取；
 * 枚举失败或结果为空一律抛异常 fail-closed，绝不静默降级为「按 default 跑一遍」——
 * 降级会让多租户后台任务只服务默认租户，属于参考项目反复警告的「单租户能跑通掩盖
 * 多租户静默停摆」。</p>
 */
public final class JdbcTenantDirectory implements TenantDirectory {

    private final JdbcTemplate jdbc;

    public JdbcTenantDirectory(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public List<String> activeTenantIds() {
        List<String> tenants;
        try {
            tenants = jdbc.queryForList(
                    "SELECT tenant_id FROM rag_tenant "
                            + "WHERE status = 'ACTIVE' ORDER BY tenant_id",
                    String.class);
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "ACTIVE 租户枚举失败：拒绝静默降级（fail-closed）", error);
        }
        if (tenants == null || tenants.isEmpty()) {
            throw new IllegalStateException(
                    "ACTIVE 租户枚举为空：拒绝静默降级（fail-closed）");
        }
        return List.copyOf(tenants);
    }
}
