package com.springairag.core.tenant;

import java.util.Objects;

/**
 * WP10：租户级硬阈值策略（平台管理面配置，不是 billing / 充值）。
 *
 * <p>{@code enabled=false} 时该租户不施加 DAY/MONTH 聚合硬阈值，
 * 既有 per-principal 预算策略仍独立生效。</p>
 */
public record TenantQuotaPolicy(
        String tenantId,
        boolean enabled,
        long dailyTokenLimit,
        long monthlyTokenLimit) {

    public TenantQuotaPolicy {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantId.isBlank() || !tenantId.matches("^[a-z0-9][a-z0-9_-]{0,63}$")) {
            throw new IllegalArgumentException("tenantId must be a tenant slug");
        }
        if (dailyTokenLimit <= 0 || monthlyTokenLimit < dailyTokenLimit) {
            throw new IllegalArgumentException(
                    "tenant quota limits must satisfy 0 < daily <= monthly");
        }
    }

    public static TenantQuotaPolicy disabled(String tenantId) {
        return new TenantQuotaPolicy(tenantId, false, 1L, 1L);
    }
}
