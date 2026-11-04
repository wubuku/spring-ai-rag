package com.springairag.core.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器安全响应头契约：CSP 覆盖必需指令且不含 {@code 'unsafe-inline'}
 * 脚本；HSTS 只在 HTTPS（直连或可信代理转发）下发；其余头在 HTTP 上
 * 也始终存在。
 */
class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void everyRequestCarriesBaselineSecurityHeaders() throws Exception {
        MockHttpServletResponse response = run(
                new MockHttpServletRequest("GET", "/webui/"));

        assertEquals(SecurityHeadersFilter.CONTENT_SECURITY_POLICY,
                response.getHeader("Content-Security-Policy"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals(SecurityHeadersFilter.PERMISSIONS_POLICY,
                response.getHeader("Permissions-Policy"));
    }

    @Test
    void contentSecurityPolicyCoversRequiredDirectivesWithoutUnsafeScripts() {
        String csp = SecurityHeadersFilter.CONTENT_SECURITY_POLICY;

        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("script-src 'self'"));
        assertTrue(csp.contains("style-src 'self'"));
        assertTrue(csp.contains("connect-src 'self'"));
        assertTrue(csp.contains("img-src 'self' data: blob:"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("object-src 'self' blob:"));

        // script-src 必须独立成段且不得含 unsafe-inline / unsafe-eval。
        assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"),
                "脚本不得放行内联");
        assertFalse(csp.contains("'unsafe-eval'"),
                "脚本不得放行 eval");
        // 唯一允许的 unsafe-inline 是 style-src-attr，用于 React 行内属性样式。
        String withoutStyleAttr = csp.replaceAll(
                "style-src-attr 'unsafe-inline'", "");
        assertFalse(withoutStyleAttr.contains("unsafe-inline"),
                "只有 style-src-attr 允许 unsafe-inline");
    }

    @Test
    void httpRequestDoesNotGetHsts() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = run(request);

        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void secureRequestGetsHsts() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/health");
        request.setSecure(true);

        MockHttpServletResponse response = run(request);

        assertEquals(SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY,
                response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void forwardedHttpsBehindTrustedProxyGetsHsts() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader("X-Forwarded-Proto", "https");

        MockHttpServletResponse response = run(request);

        assertEquals(SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY,
                response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void forwardedProtoListUsesFirstValue() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader("X-Forwarded-Proto", "https, http");

        MockHttpServletResponse response = run(request);

        assertEquals(SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY,
                response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void nonHttpsForwardedProtoDoesNotGetHsts() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader("X-Forwarded-Proto", "http");

        MockHttpServletResponse response = run(request);

        assertNull(response.getHeader("Strict-Transport-Security"));
    }

}
