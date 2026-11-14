package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WP3：启动模式守卫（实施规划 §0 / §5.6 / §6 WP3）。
 *
 * <ul>
 *   <li>SINGLE：校验凭据表 {@code rag_api_key} / {@code rag_api_principal} 的
 *       {@code tenant_id} 全为 {@code default}；存在非 default 凭据即拒绝启动
 *       （fail-closed：防止「关 RLS + 多租户凭据」形成越权路径）。</li>
 *   <li>STRICT：委托 {@link TenantRlsVerifier} 全项校验（三角色 / RLS 覆盖 /
 *       policy 语义 / 无 GUC 0 行）。</li>
 * </ul>
 */
public final class TenantModeGuard {

    private final JdbcTemplate jdbc;
    private final TenantRlsVerifier rlsVerifier;

    /** 生产装配：verifier 用默认 JDBC 实现。 */
    public TenantModeGuard(JdbcTemplate jdbc) {
        this(jdbc, new TenantRlsVerifier.JdbcTenantRlsVerifier(jdbc));
    }

    /** 测试 / 替身注入构造。 */
    public TenantModeGuard(JdbcTemplate jdbc, TenantRlsVerifier rlsVerifier) {
        this.jdbc = jdbc;
        this.rlsVerifier = rlsVerifier;
    }

    /**
     * 按模式执行启动校验；失败抛 {@link IllegalStateException} 并拒绝启动。
     *
     * @param mode 当前租户运行模式
     */
    public void verify(TenantMode mode) {
        if (mode == TenantMode.STRICT) {
            rlsVerifier.verify();
            return;
        }
        verifySingleModeCredentials();
    }

    private void verifySingleModeCredentials() {
        long keyCount = countNonDefault("rag_api_key");
        long principalCount = countNonDefault("rag_api_principal");
        if (keyCount > 0 || principalCount > 0) {
            throw new IllegalStateException(
                    "SINGLE 模式拒绝启动：rag_api_key 存在 " + keyCount + " 条、"
                            + "rag_api_principal 存在 " + principalCount + " 条非 default 凭据。"
                            + "回退前须将这些凭据清理为 tenant_id='default'，或切回 STRICT 模式；"
                            + "严禁在关闭 RLS 的同时保留多租户凭据。");
        }
    }

    private long countNonDefault(String table) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id <> 'default'", Long.class);
        return count == null ? 0 : count;
    }
}
