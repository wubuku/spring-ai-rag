package com.springairag.core.filter;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认证边界的负向套件：把“打不进去”当成被测对象。
 *
 * <p>覆盖 Authorization 头变形、两套凭据冲突、root 模式查询参数拒绝，
 * 以及“认证失败不消耗限流额度、限流也不掩盖 401”的过滤器组合。
 * 这些用例不需要数据库，属于默认 {@code mvn test} 快层；
 * 轮换/撤销后的真实即时失效由 gated PostgreSQL 套件
 * {@code ManagedApiPrincipalPostgresIntegrationTest} 覆盖。
 */
class ApiKeyAuthBoundaryNegativeTest {

    private static final String VALID_KEY = "rag_sk_boundary_valid_2026";
    private static final String ROOT_KEY = "root-boundary-2026-4f8a1c9d2e7b6a50";

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = mock(ApiKeyManagementService.class);
        when(service.authenticate(anyString())).thenAnswer(invocation ->
                VALID_KEY.equals(invocation.getArgument(0))
                        ? principal()
                        : null);
    }

    static Stream<String> malformedAuthorizationHeaders() {
        return Stream.of(
                "Bearer",
                "Bearer ",
                "bearer " + VALID_KEY,
                "BEARER " + VALID_KEY,
                "Basic " + VALID_KEY,
                "Token " + VALID_KEY,
                "Bearer\t" + VALID_KEY,
                "Bearer  " + VALID_KEY,
                "Bearer " + VALID_KEY + " trailing");
    }

    @ParameterizedTest(name = "malformed Authorization is rejected: {0}")
    @MethodSource("malformedAuthorizationHeaders")
    void malformedAuthorizationIsRejectedOnBothApiSurfaces(String header) throws Exception {
        assertRejected("/api/v1/rag/documents", header);
        assertRejected("/v1/chat/completions", header);
    }

    @Test
    void conflictingHeadersAreRejected() throws Exception {
        MockHttpServletRequest request = request("/api/v1/rag/documents");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader("Authorization", "Bearer different-key");
        assertRejected(request);
    }

    @Test
    void matchingHeadersAreAcceptedAndCarryPrincipalAttributes() throws Exception {
        MockHttpServletRequest request = request("/api/v1/rag/documents");
        request.addHeader("X-API-Key", VALID_KEY);
        request.addHeader("Authorization", "  Bearer " + VALID_KEY + "  ");

        Outcome outcome = send(filter(), request);

        assertTrue(outcome.chainInvoked());
        assertEquals("rag_p_boundary",
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE));
    }

    @Test
    void rootModeRejectsQueryStringCredentials() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                "", true, service, new EnvironmentRootCredentialResolver(ROOT_KEY));
        MockHttpServletRequest request = request("/api/v1/rag/documents");
        request.addParameter("apiKey", VALID_KEY);

        Outcome outcome = send(filter, request);

        assertFalse(outcome.chainInvoked());
        assertEquals(401, outcome.status());
    }

    @Test
    void authenticationFailuresDoNotConsumeRateLimitQuota() throws Exception {
        AtomicReference<String> clientId = new AtomicReference<>();
        AtomicBoolean served = new AtomicBoolean();
        FilterChain terminal = (req, res) -> {
            clientId.set(String.valueOf(
                    req.getAttribute(RateLimitFilter.CLIENT_ID_ATTRIBUTE)));
            served.set(true);
        };
        RateLimitFilter rateLimit = new RateLimitFilter(true, 1, "user", java.util.Map.of());

        // 5 次认证失败：认证过滤器应在限流之前终止，不能产生 429。
        for (int attempt = 0; attempt < 5; attempt++) {
            MockHttpServletRequest invalid = request("/api/v1/rag/documents");
            invalid.addHeader("Authorization", "Bearer invalid-" + attempt);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean passed = new AtomicBoolean();
            filter().doFilterInternal(invalid, response, (req, res) ->
                    rateLimit.doFilter(req, res, (r, s) -> {
                        passed.set(true);
                        terminal.doFilter(r, s);
                    }));
            assertEquals(401, response.getStatus());
            assertFalse(passed.get(), "认证失败不得进入限流之后的数据面");
        }

        // 第一个有效请求得到 200，证明失败请求没有占用本窗口额度。
        assertEquals(200, invokeWithRateLimit(filter(), rateLimit, terminal, VALID_KEY));
        assertTrue(served.get());
        assertEquals("rag_p_boundary", clientId.get());

        // 第二个有效请求才触发 429。
        assertEquals(429, invokeWithRateLimit(filter(), rateLimit, terminal, VALID_KEY));
    }

    private int invokeWithRateLimit(ApiKeyAuthFilter filter,
                                    RateLimitFilter rateLimit,
                                    FilterChain terminal,
                                    String credential) throws Exception {
        MockHttpServletRequest request = request("/api/v1/rag/documents");
        request.addHeader("X-API-Key", credential);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, (req, res) ->
                rateLimit.doFilter(req, res, terminal));
        return response.getStatus();
    }

    private void assertRejected(String path, String authorization) throws Exception {
        MockHttpServletRequest request = request(path);
        request.addHeader("Authorization", authorization);
        assertRejected(request);
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        Outcome outcome = send(filter(), request);
        assertFalse(outcome.chainInvoked());
        assertEquals(401, outcome.status());
    }

    private Outcome send(ApiKeyAuthFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilterInternal(request, response,
                (req, res) -> invoked.set(true));
        return new Outcome(response.getStatus(), invoked.get());
    }

    private ApiKeyAuthFilter filter() {
        return new ApiKeyAuthFilter("", true, service);
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private static AuthenticatedApiPrincipal principal() {
        return new AuthenticatedApiPrincipal(
                "rag_p_boundary",
                "rag_k_boundary",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                null,
                null,
                1L,
                null);
    }

    private record Outcome(int status, boolean chainInvoked) {
    }
}
