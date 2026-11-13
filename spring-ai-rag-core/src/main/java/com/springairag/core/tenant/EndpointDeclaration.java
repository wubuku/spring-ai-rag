package com.springairag.core.tenant;

import com.springairag.core.security.ApiRoutePolicy;

/**
 * WP6：单个端点的 tenant + ACL + capability 声明快照。
 *
 * <p>与 {@link ApiRoutePolicy}（capability READ/WRITE/MANAGEMENT）叠加，
 * 形成「所有写/读端点必须声明 tenant + ACL 策略」的完整快照。</p>
 */
public record EndpointDeclaration(
        String path,
        ApiRoutePolicy.Access access,
        TenantScope tenantScope,
        AclPolicy aclPolicy) {
}
