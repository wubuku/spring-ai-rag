package com.springairag.core.tenant;

/** WP6：端点必须声明的租户作用域语义。 */
public enum TenantScope {
    /** 业务端点：必须在 TenantContext 内执行，RLS 兜底。 */
    TENANT_REQUIRED,
    /** 平台管理端点：只允许登记 capability 经平台池访问，显式标注。 */
    PLATFORM_ONLY
}
