package com.springairag.core.tenant;

import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP5：请求级租户上下文过滤器（实施规划 §5.3.3 / §6 WP5）。
 *
 * <p>契约：借连接/开事务之前设置上下文；请求结束 finally 清理；
 * 解析失败（无凭据绑定等）不设置上下文，让下游按 fail-closed 处理。</p>
 */
@DisplayName("TenantContextFilter (请求级上下文)")
class TenantContextFilterTest {

    private static AuthenticatedApiPrincipal principal(String tenantId) {
        return new AuthenticatedApiPrincipal("principal-1", "cred-1", 1, "DATABASE_API_KEY",
                com.springairag.core.entity.ApiKeyRole.NORMAL, null, null, 1L, 10,
                java.util.List.of("RAG_READ"), tenantId, "ACTIVE");
    }

    @Test
    @DisplayName("解析成功 → 链内可见上下文，请求结束清理")
    void setsAndClearsContext() throws Exception {
        TenantResolver resolver = p -> java.util.Optional.ofNullable(
                p == null ? null : p.getTenantId());
        TenantContextFilter filter = new TenantContextFilter(resolver, TenantMode.STRICT);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedApiPrincipal", principal("tenant-a"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.currentTenantId();

        filter.doFilter(request, response, chain);

        assertEquals("tenant-a", seen[0]);
        assertNull(TenantContext.current(), "请求结束必须清理上下文");
    }

    @Test
    @DisplayName("解析失败 → 不设置上下文（下游 fail-closed）")
    void resolverFailureLeavesNoContext() throws Exception {
        TenantResolver resolver = p -> java.util.Optional.empty();
        TenantContextFilter filter = new TenantContextFilter(resolver, TenantMode.STRICT);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedApiPrincipal", principal("tenant-a"));
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("上下文包含 actor 与模式")
    void contextCarriesActorAndMode() throws Exception {
        TenantResolver resolver = p -> java.util.Optional.of("tenant-a");
        TenantContextFilter filter = new TenantContextFilter(resolver, TenantMode.SINGLE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedApiPrincipal", principal("tenant-a"));

        TenantContext[] captured = new TenantContext[1];
        FilterChain chain = (req, res) -> captured[0] = TenantContext.current();

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(captured[0]);
        assertEquals("tenant-a", captured[0].tenantId());
        assertEquals("principal-1", captured[0].actorId());
        assertEquals(TenantMode.SINGLE, captured[0].mode());
    }
}
