package com.springairag.core.tenant;

/** WP6：命名查询枚举；业务包不得绕过本枚举直接访问平台池。 */
public enum PlatformCapability {
    /** 跨租户发现：仅返回 TenantProjection 最小身份投影。 */
    TENANT_DISCOVERY
}
