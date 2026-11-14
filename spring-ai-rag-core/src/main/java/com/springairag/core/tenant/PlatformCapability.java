package com.springairag.core.tenant;

/**
 * WP6/WP9：命名查询枚举；业务包不得绕过本枚举直接访问平台池。
 *
 * <p>WP9 新增 {@link #TENANT_LIFECYCLE}：租户生命周期管理端点的 capability 守卫；
 * 与 {@link #TENANT_DISCOVERY} 同一语义——白名单 + 审计 + 最小投影。</p>
 */
public enum PlatformCapability {
    /** 跨租户 discovery：仅返回 TenantProjection 最小身份投影。 */
    TENANT_DISCOVERY,
    /** 租户生命周期：创建/暂停/恢复/禁用/软删；actor 白名单 + 审计。 */
    TENANT_LIFECYCLE
}
