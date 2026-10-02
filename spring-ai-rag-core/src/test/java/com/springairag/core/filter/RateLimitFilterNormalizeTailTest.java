package com.springairag.core.filter;

import com.springairag.core.ratelimit.PostgresRateLimitStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.springairag.core.ratelimit.RateLimitObservability;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RateLimitFilter 构造归一与辅助方法长尾（Batch 629，JaCoCo
 * 驱动）：构造器对 null 策略/键限流表/后端/可观测性的归一，
 * fixedPrincipalType 对非标准类型返回 UNKNOWN，
 * resolveClientIp 对 X-Forwarded-For 多级取首段。
 */
class RateLimitFilterNormalizeTailTest {

    @Test
    void constructorNormalizesNullStrategyKeyLimitsAndBackend() throws Exception {
        var filter = new RateLimitFilter(
                true, 60, null, null, null, null, null);
        // null strategy 归一为 "ip"，null keyLimits → 空表，null backend → "local"。
        // 通过公开行为间接验证：请求被 IP 限流（默认 60/min）。
        var request = new MockHttpServletRequest("GET", "/test");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        assertEquals(200, response.getStatus());
    }

    /**
     * The name promised a return value the body never looked at: it called
     * doFilter and its own comment conceded "仅验证不抛异常即可（间接覆盖分支）",
     * which is not the same thing. Any change that made fixedPrincipalType return
     * the raw attribute — leaking an unrecognised principal type straight into
     * the rate-limit observability tags — left this test green. Batch 811.
     */
    private String fixedPrincipalType(RateLimitFilter filter,
                                      MockHttpServletRequest request) throws Exception {
        var method = RateLimitFilter.class
                .getDeclaredMethod("fixedPrincipalType",
                        jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(filter, request);
    }

    @Test
    void fixedPrincipalTypeReturnsUnknownForNonStandardType() throws Exception {
        var request = new MockHttpServletRequest("GET", "/test");
        request.setAttribute("authenticatedPrincipalType", "WEIRD_TYPE");

        assertEquals("UNKNOWN", fixedPrincipalType(new RateLimitFilter(true, 60), request));
    }

    @Test
    void fixedPrincipalTypePassesThroughTheThreeKnownTypes() throws Exception {
        // The other arm of the same branch, and the one that decides whether a
        // legitimate principal is still attributed correctly in metrics.
        var filter = new RateLimitFilter(true, 60);
        for (String type : new String[] {
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC }) {
            var request = new MockHttpServletRequest("GET", "/test");
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE, type);
            assertEquals(type, fixedPrincipalType(filter, request));
        }
    }

    @Test
    void fixedPrincipalTypeIsUnknownWhenTheAttributeIsAbsent() throws Exception {
        assertEquals("UNKNOWN", fixedPrincipalType(new RateLimitFilter(true, 60),
                new MockHttpServletRequest("GET", "/test")));
    }

    @Test
    void resolveClientIpHandlesMultiLevelForwardedFor() {
        // A trusted proxy may relay a longer chain; the client identity is the
        // right-most entry that is not itself a trusted proxy, which is what
        // stops a client from injecting extra hops at the front.
        var filter = new RateLimitFilter(true, 60, "ip", java.util.Map.of(), "local", null,
                RateLimitObservability.noop(),
                TrustedProxyResolver.of(java.util.List.of("192.168.1.0/24", "10.0.0.0/8")));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 9.10.11.12");
        request.setRemoteAddr("192.168.1.1");

        // 192.168.1.1 (peer, trusted) -> 9.10.11.12 untrusted, so that is the client
        assertEquals("9.10.11.12", filter.resolveClientIp(request));
    }

    @Test
    void resolveClientIpIgnoresAForgedLeftmostHop() {
        // The classic bypass: prepend junk to X-Forwarded-For. Walking from the
        // right and stopping at the first untrusted hop means the attacker's
        // injected value is never reached.
        var filter = new RateLimitFilter(true, 60, "ip", java.util.Map.of(), "local", null,
                RateLimitObservability.noop(),
                TrustedProxyResolver.of(java.util.List.of("192.168.1.0/24")));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "6.6.6.6, 8.8.8.8");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("8.8.8.8", filter.resolveClientIp(request));
    }

    @Test
    void resolveClientIpFallsBackToRemoteAddrWithoutHeader() {
        var filter = new RateLimitFilter(true, 60);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertEquals("10.0.0.1", filter.resolveClientIp(request));
    }

    @Test
    void isExcludedPathCoversAllExclusionPrefixes() {
        var filter = new RateLimitFilter(true, 60);
        assertTrue(filter.isExcludedPath("/actuator/health"));
        assertTrue(filter.isExcludedPath("/swagger-ui/index.html"));
        assertTrue(filter.isExcludedPath("/v3/api-docs"));
        assertTrue(filter.isExcludedPath("/health"));
        assertTrue(filter.isExcludedPath("/error"));
        assertTrue(!filter.isExcludedPath("/v1/chat"));
    }
}
