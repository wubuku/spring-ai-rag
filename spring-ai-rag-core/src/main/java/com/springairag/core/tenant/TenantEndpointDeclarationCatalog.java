package com.springairag.core.tenant;

import com.springairag.core.security.ApiRoutePolicy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WP12：启动期端点声明覆盖（规划 §6 WP6 收口 / §7 Endpoint guard）。
 *
 * <p>输入契约是 {@code ApiRoutePolicyRegistry.describe()} 的真实 MVC 映射快照
 * （格式 {@code METHOD /pattern -> DECISION}）。本类完成三件事：</p>
 * <ol>
 *   <li>把每条受保护路由解析成一个 {@link EndpointDeclaration}，默认裁决为
 *       {@code TENANT_REQUIRED + COLLECTION_ACL}；</li>
 *   <li>平台 capability 端点由 {@link #PLATFORM_CAPABILITY_OVERRIDES} **人工语义白名单**
 *       显式覆盖为 {@code PLATFORM_ONLY + PLATFORM_CAPABILITY}。不能按
 *       {@code /api/v1/platform/} 前缀猜：本仓真实租户生命周期端点是
 *       {@code /api/v1/rag/tenants}；</li>
 *   <li>白名单路径若出现在 describe() 快照里，其 decision 必须为
 *       {@code MANAGEMENT}；否则 {@link Projection#validate()} fail-closed，
 *       防止注解变更让平台裁决静默失效。白名单路径**不在**快照时不报错：
 *       平台端点由 {@code @ConditionalOnBean} 条件装配，在无
 *       {@code TenantLifecycleService} 的上下文（如契约测试切片）本就不存在；
 *       「平台端点确实已装配」的断言由平台面专项测试负责。本仓其余约 28 个
 *       {@code MANAGEMENT} 端点（api-keys / alerts / audit-logs / auth 等）是
 *       **租户内管理能力**，按基线裁决为 {@code TENANT_REQUIRED + COLLECTION_ACL}。</li>
 * </ol>
 *
 * <p>「新增端点漏声明 → 红」由 {@link TenantEndpointGuard#validate(Set)} 保证：
 * 调用方（{@code TenantStartupGuardRunner} / 契约测试）以真实 describe() 为
 * actualPaths、以本投影为 declarations 做差集；本类不自己生成 actualPaths 的
 * 副本去校验自己。{@code OpenApiContractTest} 用真实 Spring MVC 上下文反复证明
 * 解析契约与白名单裁决仍然匹配现实路由表。</p>
 */
public final class TenantEndpointDeclarationCatalog {

    private static final String API_PREFIX = "/api/";
    private static final String COMPAT_PREFIX = "/v1/";
    private static final String PLATFORM_PREFIX = "/api/v1/platform/";
    private static final String MANAGEMENT_DECISION = "MANAGEMENT";

    /**
     * 人工语义裁决：平台 capability 端点（不按路径前缀猜）。
     *
     * <p>新增平台端点必须同时加入本白名单；否则 MANAGEMENT 决策会触发
     * {@link Projection#validate()} 的未裁决拒绝。本仓当前唯一平台端点为
     * 租户生命周期 {@code /api/v1/rag/tenants}。</p>
     */
    private static final Set<String> PLATFORM_CAPABILITY_OVERRIDES = Set.of(
            "/api/v1/rag/tenants");

    private TenantEndpointDeclarationCatalog() {
    }

    /** 从 registry.describe() 解析出显式声明集合与真实路径集合（两个独立视图）。 */
    public static Projection fromDescriptions(List<String> descriptions) {
        Map<String, EndpointDeclaration> declarations = new LinkedHashMap<>();
        Set<String> actualPaths = new LinkedHashSet<>();
        Set<String> invalidPlatformOverrides = new LinkedHashSet<>();
        for (String line : descriptions) {
            Parsed parsed = parse(line);
            actualPaths.add(parsed.path());
            boolean platformOverride = PLATFORM_CAPABILITY_OVERRIDES.contains(parsed.path());
            if (platformOverride) {
                if (!MANAGEMENT_DECISION.equals(parsed.decision())) {
                    invalidPlatformOverrides.add(parsed.path() + " -> " + parsed.decision());
                }
                declarations.put(parsed.path(), new EndpointDeclaration(
                        parsed.path(), ApiRoutePolicy.Access.MANAGEMENT,
                        TenantScope.PLATFORM_ONLY, AclPolicy.PLATFORM_CAPABILITY));
            } else {
                declarations.put(parsed.path(), new EndpointDeclaration(
                        parsed.path(), toAccess(parsed.decision()),
                        TenantScope.TENANT_REQUIRED, AclPolicy.COLLECTION_ACL));
            }
        }
        return new Projection(Set.copyOf(declarations.values()),
                Set.copyOf(actualPaths),
                Set.copyOf(invalidPlatformOverrides));
    }

    private static ApiRoutePolicy.Access toAccess(String decision) {
        return switch (decision) {
            case "READ" -> ApiRoutePolicy.Access.READ;
            case "WRITE" -> ApiRoutePolicy.Access.WRITE;
            case "MANAGEMENT" -> ApiRoutePolicy.Access.MANAGEMENT;
            default -> ApiRoutePolicy.Access.METHOD_DEFAULT;
        };
    }

    private static Parsed parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalStateException("TenantEndpointDeclarationCatalog: empty route description");
        }
        int arrow = line.indexOf(" -> ");
        if (arrow < 0) {
            throw new IllegalStateException(
                    "TenantEndpointDeclarationCatalog: malformed route description: " + line);
        }
        String left = line.substring(0, arrow).trim();
        String decision = line.substring(arrow + 4).trim();
        int space = left.indexOf(' ');
        if (space <= 0 || space == left.length() - 1 || decision.isEmpty()) {
            throw new IllegalStateException(
                    "TenantEndpointDeclarationCatalog: malformed route description: " + line);
        }
        String path = left.substring(space + 1).trim();
        if (!path.startsWith(API_PREFIX) && !path.startsWith(COMPAT_PREFIX)
                && !path.startsWith(PLATFORM_PREFIX)) {
            throw new IllegalStateException(
                    "TenantEndpointDeclarationCatalog: unexpected protected path: " + path);
        }
        return new Parsed(path, decision);
    }

    private record Parsed(String path, String decision) {
    }

    /** 显式声明 + 启动期真实路径 + 平台白名单误标诊断（fail-closed）。 */
    public record Projection(
            Set<EndpointDeclaration> declarations,
            Set<String> actualPaths,
            Set<String> invalidPlatformOverrides) {

        public TenantEndpointGuard guard() {
            return new TenantEndpointGuard(declarations);
        }

        public void validate() {
            if (!invalidPlatformOverrides.isEmpty()) {
                throw new IllegalStateException(
                        "平台端点 @ApiRoutePolicy 必须保持 MANAGEMENT capability："
                                + invalidPlatformOverrides);
            }
            guard().validate(actualPaths);
        }
    }
}
