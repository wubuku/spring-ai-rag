package com.springairag.core.tenant;

import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WP11 红队：header 伪造不得决定租户身份。
 *
 * <p>{@link TenantContextFilter} 只接受认证链写入的 principal attribute；
 * 请求头里伪造 X-Tenant-Id 不得产生 TenantContext，也不得被 resolver 看见。</p>
 */
@DisplayName("WP11 red team: header forgery cannot choose a tenant")
class TenantHeaderForgeryTest {

    private static AuthenticatedApiPrincipal principal(String tenantId) {
        return new AuthenticatedApiPrincipal("principal-1", "cred-1", 1, "DATABASE_API_KEY",
                com.springairag.core.entity.ApiKeyRole.NORMAL, null, null, 1L, 10,
                List.of("RAG_READ"), tenantId, "ACTIVE");
    }

    @Test
    @DisplayName("伪造 X-Tenant-Id 且无已认证 principal → 无上下文（RLS 0 行 fail-closed）")
    void forgedTenantHeaderWithoutPrincipalIsIgnored() throws Exception {
        TenantResolver resolver = p -> java.util.Optional.ofNullable(
                p == null ? null : p.getTenantId());
        TenantContextFilter filter = new TenantContextFilter(resolver, TenantMode.STRICT);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant-b");
        request.addHeader("X-Tenant", "tenant-b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.currentTenantId();
        filter.doFilter(request, response, chain);

        assertNull(seen[0], "无 principal 时 header 不得变成租户上下文");
        assertNull(TenantContext.current(), "请求结束必须清理上下文");
    }

    @Test
    @DisplayName("伪造 X-Tenant-Id 且已有认证 principal → 租户来自 principal 绑定，不来自 header")
    void forgedTenantHeaderCannotOverrideAuthenticatedPrincipal() throws Exception {
        TenantResolver resolver = p -> java.util.Optional.ofNullable(
                p == null ? null : p.getTenantId());
        TenantContextFilter filter = new TenantContextFilter(resolver, TenantMode.STRICT);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant-b");
        request.setAttribute("authenticatedApiPrincipal", principal("tenant-a"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.currentTenantId();
        filter.doFilter(request, response, chain);

        assertEquals("tenant-a", seen[0], "租户必须来自已认证 principal 绑定，不能被 header 覆盖");
    }
}
