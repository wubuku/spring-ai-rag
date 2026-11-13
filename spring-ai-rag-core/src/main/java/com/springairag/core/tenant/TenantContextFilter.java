package com.springairag.core.tenant;

import com.springairag.core.filter.ApiKeyAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * WP5：请求级租户上下文过滤器（实施规划 §5.3.3 / §6 WP5）。
 *
 * <p>必须在认证过滤器（order -10）与 PrincipalMdcFilter（order -9）之后执行，
 * 借连接/开事务之前完成 {@link TenantContext} 绑定；请求结束 finally 清理。</p>
 *
 * <p>解析顺序由装配层保证：宿主可信 resolver → 凭据绑定 → 单租户 default。
 * 解析失败不设置上下文，让下游 RLS 返回 0 行、服务层断言拒绝写路径（fail-closed），
 * 绝不静默落回 default。</p>
 *
 * <p>MDC 写入 tenant/actor，不写 raw credential；恢复线程原值避免线程池串号。</p>
 */
public class TenantContextFilter extends OncePerRequestFilter {

    /** MDC key：租户 id。 */
    public static final String TENANT_ID_KEY = "tenantId";

    private final TenantResolver resolver;
    private final TenantMode mode;

    public TenantContextFilter(TenantResolver resolver, TenantMode mode) {
        this.resolver = resolver;
        this.mode = mode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object principalAttr = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        com.springairag.core.security.AuthenticatedApiPrincipal principal =
                principalAttr instanceof com.springairag.core.security.AuthenticatedApiPrincipal p
                        ? p : null;

        Optional<String> tenantId = resolver.resolveTenantId(principal);
        if (tenantId.isEmpty()) {
            // fail-closed：不设置上下文。RLS 0 行 + 服务层写断言兜底。
            filterChain.doFilter(request, response);
            return;
        }

        String previousTenant = MDC.get(TENANT_ID_KEY);
        MDC.put(TENANT_ID_KEY, tenantId.get());
        try {
            TenantContext.set(new TenantContext(
                    tenantId.get(),
                    principal == null ? null : principal.getPrincipalId(),
                    mode));
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            if (previousTenant == null) {
                MDC.remove(TENANT_ID_KEY);
            } else {
                MDC.put(TENANT_ID_KEY, previousTenant);
            }
        }
    }
}
