package com.springairag.core.tenant;

/**
 * 租户运行模式（WP3，实施规划 §0 / §5.6）。
 *
 * <ul>
 *   <li>{@link #SINGLE}：单租户默认模式；resolver 固定 {@code default}；不启用 RLS 与
 *       verifier；启动时校验凭据 {@code tenant_id} 全为 {@code default}（TenantModeGuard）。</li>
 *   <li>{@link #STRICT}：多租户强制模式；必须配置 app NOBYPASSRLS、migrator、platform 三角色；
 *       RLS ENABLE+FORCE；启动时执行 TenantRlsVerifier 全项校验。</li>
 * </ul>
 */
public enum TenantMode {
    SINGLE,
    STRICT
}
