package com.springairag.core.tenant;

import com.springairag.core.security.AuthenticatedApiPrincipal;

import java.util.Optional;

/**
 * WP5：STRICT 模式的凭据绑定解析器（规划 §5.2 / §5.3.2）。
 *
 * <p>认证查找走 app 角色的 digest 精确查询（凭据表无 RLS），加载后由本解析器校验：
 * tenant_id 非空、租户状态 ACTIVE，并直接返回绑定 tenant。空值一律 fail-closed，
 * 不落回 default，避免「关 RLS + 多租户凭据」越权路径。</p>
 */
public final class PrincipalTenantResolver implements TenantResolver {

    public static final String ACTIVE_STATUS = "ACTIVE";

    /** 非 ACTIVE 租户拒绝语义（供 TenantContextFilter 边缘短路返回显式 403）。 */
    public record TenantDenial(String errorCode, String message) {
    }

    /**
     * 对已认证的数据库 API Key 主体评估租户拒绝原因；ACTIVE 或无租户归属时返回 empty。
     */
    public static Optional<TenantDenial> evaluateDenial(AuthenticatedApiPrincipal principal) {
        if (principal == null || principal.getTenantId() == null
                || principal.getTenantId().isBlank()) {
            return Optional.empty();
        }
        String status = principal.getTenantStatus();
        if (ACTIVE_STATUS.equalsIgnoreCase(status)) {
            return Optional.empty();
        }
        if ("SUSPENDED".equalsIgnoreCase(status)) {
            return Optional.of(new TenantDenial(
                    "TENANT_SUSPENDED",
                    "Tenant '" + principal.getTenantId() + "' is suspended."));
        }
        if ("DISABLED".equalsIgnoreCase(status)) {
            return Optional.of(new TenantDenial(
                    "TENANT_DISABLED",
                    "Tenant '" + principal.getTenantId() + "' is disabled."));
        }
        return Optional.of(new TenantDenial(
                "TENANT_INACTIVE",
                "Tenant '" + principal.getTenantId() + "' is not active."));
    }

    @Override
    public Optional<String> resolveTenantId(AuthenticatedApiPrincipal principal) {
        if (principal == null || principal.getTenantId() == null
                || principal.getTenantId().isBlank()) {
            return Optional.empty();
        }
        if (!ACTIVE_STATUS.equalsIgnoreCase(principal.getTenantStatus())) {
            return Optional.empty();
        }
        return Optional.of(principal.getTenantId());
    }
}
