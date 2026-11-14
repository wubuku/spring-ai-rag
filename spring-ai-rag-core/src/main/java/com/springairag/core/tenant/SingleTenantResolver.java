package com.springairag.core.tenant;

import com.springairag.core.security.AuthenticatedApiPrincipal;

import java.util.Optional;

/**
 * WP5：SINGLE 模式的固定解析器（规划 §0 / §5.3.2）。
 *
 * <p>单租户模式仍写 tenant_id、仍走同一 SQL/任务路径，只是固定解析到 {@code default}，
 * 并关闭 RLS 与 verifier。不读取 principal，不读取任何 header。</p>
 */
public final class SingleTenantResolver implements TenantResolver {

    public static final String DEFAULT_TENANT_ID = "default";

    @Override
    public Optional<String> resolveTenantId(AuthenticatedApiPrincipal principal) {
        return Optional.of(DEFAULT_TENANT_ID);
    }
}
