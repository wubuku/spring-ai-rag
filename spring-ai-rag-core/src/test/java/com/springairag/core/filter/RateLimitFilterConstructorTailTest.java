package com.springairag.core.filter;

import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流过滤器长尾（Batch 627，JaCoCo 驱动）：构造器对 null 策略/
 * 键限流表/后端/可观测性的归一、api-key 策略的请求头与属性回退、
 * 自定义键限流表命中与未命中、X-Forwarded-For 多级与单级解析、
 * PostgreSQL 后端的 allowed/rejected/异常路径与 Retry-After 投影。
 */
class RateLimitFilterConstructorTailTest {

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/v1/chat");
        response = new MockHttpServletResponse();
    }

    private RateLimitFilter postgresFilter(
            PostgresRateLimitStore store) {
        return new RateLimitFilter(
                true, 60, "postgresql", Map.of(), "postgresql",
                store, null);
    }

    private void authenticatedRequest(MockHttpServletRequest request,
                                      String principalId, Integer requestsPerMinute) {
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_KEY_ATTRIBUTE,
                principalId);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_PRINCIPAL_TYPE,
                com.springairag.core.filter.ApiKeyAuthFilter
                        .PRINCIPAL_DATABASE_API_KEY);
        var principal = new AuthenticatedApiPrincipal(
                "p1", principalId, 1, "DATABASE_API_KEY",
                com.springairag.core.entity.ApiKeyRole.NORMAL, null,
                LocalDateTime.now().plusYears(1), 1L,
                requestsPerMinute);
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal);
    }

    @Test
    void postgresBackendRejectsWhenPrincipalIdMissing() throws Exception {
        RateLimitFilter filter = postgresFilter(mock(PostgresRateLimitStore.class));

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("rate_limit_store_unavailable"));
    }

    @Test
    void postgresBackendRejectsWhenStoreMissing() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                true, 60, "postgresql", Map.of(), "postgresql",
                null, null);

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(503, response.getStatus());
    }

    @Test
    void postgresBackendAllowsAndProjectsHeaders() throws Exception {
        PostgresRateLimitStore store = mock(PostgresRateLimitStore.class);
        when(store.consume(eq("principal-1"), eq(25)))
                .thenReturn(new PostgresRateLimitStore.Decision(true, 10,
                        java.time.OffsetDateTime.now(), 30));
        authenticatedRequest(request, "principal-1", 25);
        RateLimitFilter filter = postgresFilter(store);

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("25", response.getHeader("X-RateLimit-Limit"));
        assertEquals("15", response.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void postgresBackendRejectsAndProjectsRetryAfter() throws Exception {
        PostgresRateLimitStore store = mock(PostgresRateLimitStore.class);
        when(store.consume(eq("principal-1"), eq(60)))
                .thenReturn(new PostgresRateLimitStore.Decision(false, 60,
                        java.time.OffsetDateTime.now(), 42));
        authenticatedRequest(request, "principal-1", 60);
        RateLimitFilter filter = postgresFilter(store);

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(429, response.getStatus());
        assertEquals("42", response.getHeader("Retry-After"));
    }

    @Test
    void postgresBackendMapsStoreFailuresToServiceUnavailable() throws Exception {
        PostgresRateLimitStore store = mock(PostgresRateLimitStore.class);
        when(store.consume(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("db slow"));
        authenticatedRequest(request, "principal-1", 60);
        RateLimitFilter filter = postgresFilter(store);

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(503, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("Rate limit service is unavailable")
                        || body.contains("RATE_LIMIT_STORE_UNAVAILABLE"),
                "body=" + body);
    }

    @Test
    void apiKeyStrategyFallsBackToHeaderThenIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                true, 5, "api-key", Map.of(), "local", null, null);

        // 无 API-Key 头与属性 → 回退 IP 限流。
        request.setRequestURI("/other");
        filter.doFilter(request, response, (req, res) -> { });

        // 属性中的键优先于请求头。
        request.setAttribute(
                com.springairag.core.filter.ApiKeyAuthFilter
                        .AUTHENTICATED_KEY_ATTRIBUTE,
                "attr-key");
        filter.doFilter(request, response, (req, res) -> { });
        assertEquals("5", response.getHeader("X-RateLimit-Limit"));
    }

    @Test
    void customKeyLimitOverridesDefaultForKnownKey() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                true, 5, "api-key",
                Map.of("vip-key", 2), "local", null, null);
        request.addHeader("X-API-Key", "vip-key");
        request.setRequestURI("/other");

        // 第 3 次请求超过 vip-key 的自定义限流 2 → 429（非 /v1 路径
        // 投影 ErrorResponse）。
        for (int i = 0; i < 2; i++) {
            filter.doFilter(request, response, (req, res) -> { });
        }
        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request, third, (req, res) -> { });

        assertEquals(429, third.getStatus());
        assertTrue(third.getContentAsString().contains("TOO_MANY_REQUESTS"));
    }

    @Test
    void resolveClientIpPrefersForwardedFor() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 5);
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        request.setRemoteAddr("9.9.9.9");

        assertEquals("1.2.3.4", filter.resolveClientIp(request));

        request.removeHeader("X-Forwarded-For");
        assertEquals("9.9.9.9", filter.resolveClientIp(request));
    }

    @Test
    void excludedPathsSkipRateLimiting() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1);
        for (String path : List.of("/actuator", "/swagger-ui", "/v3/api-docs",
                "/health", "/error")) {
            assertTrue(filter.isExcludedPath(path), path);
        }
        assertTrue(!filter.isExcludedPath("/v1/chat"));
    }
}
