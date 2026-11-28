package com.springairag.core.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.filter.FailureClassHttpSupport;
import com.springairag.core.filter.NativeErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * 在 STRICT 模式下，若已认证的数据库凭据所属租户处于 SUSPENDED / DISABLED / 软删态，
 * 本过滤器在边缘立即短路返回显式 {@code 403 Forbidden}（{@code TENANT_SUSPENDED} /
 * {@code TENANT_DISABLED} / {@code TENANT_INACTIVE}），避免退化为误导性的 200 空列表。</p>
 *
 * <p>MDC 写入 tenant/actor，不写 raw credential；恢复线程原值避免线程池串号。</p>
 */
public class TenantContextFilter extends OncePerRequestFilter {

    /** MDC key：租户 id。 */
    public static final String TENANT_ID_KEY = "tenantId";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            // P0-3 修复：STRICT 模式下，已认证数据库主体归属非 ACTIVE 租户时边缘显式 403 短路。
            if (mode == TenantMode.STRICT && principal != null) {
                Optional<PrincipalTenantResolver.TenantDenial> denial =
                        PrincipalTenantResolver.evaluateDenial(principal);
                if (denial.isPresent()) {
                    sendTenantForbidden(request, response, denial.get());
                    return;
                }
            }
            // 未认证/公开/root 路径：不设置上下文，RLS 0 行 + 服务层写断言兜底。
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

    private void sendTenantForbidden(HttpServletRequest request,
                                     HttpServletResponse response,
                                     PrincipalTenantResolver.TenantDenial denial) throws IOException {
        String path = request.getRequestURI();
        int status = HttpStatus.FORBIDDEN.value();
        if (path != null && path.startsWith("/v1/")) {
            FailureClassHttpSupport.apply(response, status, denial.errorCode());
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                    OpenAiErrorResponse.of(
                            denial.message(),
                            "permission_error",
                            null,
                            denial.errorCode().toLowerCase())));
            return;
        }
        NativeErrorResponseWriter.write(
                OBJECT_MAPPER, request, response, status,
                denial.errorCode(), denial.message());
    }
}
