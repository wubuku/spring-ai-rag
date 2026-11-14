package com.springairag.core.tenant;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WP6：端点 guard 覆盖校验（实施规划 §6 WP6 / §7）。
 *
 * <p>比较「已声明端点集合」与「实际受保护端点集合」：</p>
 * <ul>
 *   <li>实际端点漏声明 → 启动失败（新端点漏声明即红）；</li>
 *   <li>声明缺 {@link TenantScope} / {@link AclPolicy} → 启动失败；</li>
 *   <li>平台路径（{@code /api/v1/platform/}）必须显式 {@link TenantScope#PLATFORM_ONLY}，
 *       防止业务端点误接平台池。</li>
 * </ul>
 */
public final class TenantEndpointGuard {

    private static final String PLATFORM_PREFIX = "/api/v1/platform/";

    private final Set<EndpointDeclaration> declarations;

    public TenantEndpointGuard(Set<EndpointDeclaration> declarations) {
        this.declarations = Set.copyOf(declarations);
    }

    /** 校验全部实际端点均已完整声明；任何缺口抛 IllegalStateException。 */
    public void validate(Set<String> actualPaths) {
        Set<String> declaredPaths = new LinkedHashSet<>();
        for (EndpointDeclaration d : declarations) {
            if (d.tenantScope() == null) {
                throw new IllegalStateException(
                        "端点声明缺 TENANT 策略：" + d.path() + " 必须声明 TENANT_REQUIRED 或 PLATFORM_ONLY");
            }
            if (d.aclPolicy() == null) {
                throw new IllegalStateException(
                        "端点声明缺 ACL 策略：" + d.path() + " 必须声明 COLLECTION_ACL 或 PLATFORM_CAPABILITY");
            }
            if (d.path().startsWith(PLATFORM_PREFIX)
                    && d.tenantScope() != TenantScope.PLATFORM_ONLY) {
                throw new IllegalStateException(
                        "平台端点必须显式声明 PLATFORM_ONLY：" + d.path());
            }
            declaredPaths.add(d.path());
        }
        Set<String> missing = new LinkedHashSet<>(actualPaths);
        missing.removeAll(declaredPaths);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "新增/受保护端点漏声明 tenant + ACL 策略：" + missing);
        }
    }

    public Set<EndpointDeclaration> declarations() {
        return declarations;
    }
}
