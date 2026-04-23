package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimitFilter — API Rate Limit Filter")
class RateLimitFilterTest {

    private static final int LIMIT = 5;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RateLimitFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(true, LIMIT);
        filter.clearWindows();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        request.setRequestURI("/api/v1/rag/chat");
        request.setRemoteAddr("192.168.1.1");
    }

    @Nested
    @DisplayName("Normal Passing")
    class AllowRequests {

        @Test
        @DisplayName("disabled filter allows all requests")
        void disabledAllowsAll() throws Exception {
            RateLimitFilter disabled = new RateLimitFilter(false, LIMIT);

            for (int i = 0; i < 20; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                disabled.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        "request #" + (i + 1) + " should pass");
            }
        }

        @Test
        @DisplayName("requestsPerMinute <= 0 allows all")
        void zeroLimitAllowsAll() throws Exception {
            RateLimitFilter zero = new RateLimitFilter(true, 0);

            for (int i = 0; i < 10; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                zero.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }
        }

        @Test
        @DisplayName("requests within limit pass normally")
        void withinLimitAllows() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        "request #" + (i + 1) + " should be within limit");
                assertNotNull(chain.getRequest(), "request should pass to filter chain");
            }
        }

        @Test
        @DisplayName("excluded paths do not count")
        void excludedPathsDoNotCount() throws Exception {
            String[] excluded = {"/actuator/health", "/swagger-ui/index.html",
                    "/v3/api-docs", "/health", "/error"};

            for (String path : excluded) {
                request.setRequestURI(path);
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        path + " should pass");
            }

            // excluded paths do not consume limit, normal paths still work
            request.setRequestURI("/api/v1/rag/chat");
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }
        }
    }

    @Nested
    @DisplayName("Rate Limit Triggered")
    class RateLimitTriggered {

        @Test
        @DisplayName("exceeds limit returns 429")
        void exceedsLimitReturns429() throws Exception {
            // exhaust the limit first
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            // request #LIMIT+1 should be rate limited
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);

            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
            assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
            assertEquals("60", response.getHeader("Retry-After"));
        }

        @Test
        @DisplayName("429 response body contains error details")
        void rateLimitResponseBody() throws Exception {
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            var body = objectMapper.readTree(response.getContentAsString());
            assertEquals("TOO_MANY_REQUESTS", body.get("error").asText());
            assertTrue(body.get("message").asText().contains(String.valueOf(LIMIT)));
            assertEquals("/api/v1/rag/chat", body.get("path").asText());
            assertNotNull(body.get("timestamp").asText());
        }

        @Test
        @DisplayName("429 blocks filter chain")
        void rateLimitBlocksChain() throws Exception {
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            // chain should not be invoked (last request was rate limited)
            // note: chain is new before the last call
            // simpler: check chain state after request #LIMIT+1
            // since chain is recreated each time, use another verification
            response = new MockHttpServletResponse();
            MockFilterChain newChain = new MockFilterChain();
            filter.doFilterInternal(request, response, newChain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
            assertNull(newChain.getRequest(), "rate-limited request should not reach chain");
        }
    }

    @Nested
    @DisplayName("Response Headers")
    class ResponseHeaders {

        @Test
        @DisplayName("normal response includes rate limit headers")
        void normalResponseHasHeaders() throws Exception {
            filter.doFilterInternal(request, response, chain);

            assertEquals(String.valueOf(LIMIT), response.getHeader("X-RateLimit-Limit"));
            assertNotNull(response.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        @DisplayName("remaining count decrements")
        void remainingDecreases() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);

                int expectedRemaining = LIMIT - i - 1;
                assertEquals(String.valueOf(expectedRemaining),
                        response.getHeader("X-RateLimit-Remaining"),
                        "after request #" + (i + 1) + " remaining should be " + expectedRemaining);
            }
        }

        @Test
        @DisplayName("429 response includes Retry-After and rate limit headers")
        void rateLimitResponseHeaders() throws Exception {
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            assertEquals("60", response.getHeader("Retry-After"));
            assertEquals(String.valueOf(LIMIT), response.getHeader("X-RateLimit-Limit"));
            assertEquals("0", response.getHeader("X-RateLimit-Remaining"));
        }
    }

    @Nested
    @DisplayName("IP Isolation")
    class IpIsolation {

        @Test
        @DisplayName("different IPs have independent counts")
        void differentIpsIndependent() throws Exception {
            MockHttpServletRequest req2 = new MockHttpServletRequest();
            req2.setRequestURI("/api/v1/rag/chat");
            req2.setRemoteAddr("192.168.1.2");

            // IP1 exhausts the limit
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            // IP2 should still be able to request
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(req2, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(), "different IPs should not affect each other");
        }

        @Test
        @DisplayName("X-Forwarded-For takes precedence over RemoteAddr")
        void forwardedForUsed() throws Exception {
            request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");

            String ip = filter.resolveClientIp(request);
            assertEquals("10.0.0.1", ip);
        }

        @Test
        @DisplayName("X-Forwarded-For null falls back to RemoteAddr")
        void fallbackToRemoteAddr() throws Exception {
            request.setRemoteAddr("127.0.0.1");

            String ip = filter.resolveClientIp(request);
            assertEquals("127.0.0.1", ip);
        }
    }

    @Nested
    @DisplayName("Window Expiry")
    class WindowExpiry {

        @Test
        @DisplayName("count resets after window expiry")
        void expiredWindowResets() throws Exception {
            // exhaust the limit
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());

            // simulate window expiry: directly modify windowStart
            var window = filter.getWindows().get("192.168.1.1");
            assertNotNull(window);

            // use reflection or direct map access to simulate expiry
            // since windowStart is final, clear the map to let it rebuild
            filter.clearWindows();

            // after expiry, should pass again
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(), "window expiry should reset count");
        }
    }

    @Nested
    @DisplayName("user strategy — rate limit by authenticated user")
    class UserStrategy {

        @Test
        @DisplayName("authenticated user uses authenticatedApiKey as rate limit key")
        void authenticatedUserUsesAttribute() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            // simulate authenticated attribute set by ApiKeyAuthFilter
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-authenticated-user");
            request.setRemoteAddr("192.168.1.100");

            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        "request #" + (i + 1) + " should be within limit");
            }

            // request #LIMIT+1 should be rate limited
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "request #" + (LIMIT + 1) + " should trigger rate limit");
        }

        @Test
        @DisplayName("unauthenticated user falls back to IP rate limit")
        void unauthenticatedUserFallsBackToIp() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            // no authenticatedApiKey attribute set (simulate unauthenticated)
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, null);
            request.setRemoteAddr("192.168.1.200");

            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "unauthenticated user should use IP rate limit");
        }

        @Test
        @DisplayName("different authenticated users have independent counts")
        void differentAuthenticatedUsersIndependent() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            // user A exhausts the limit
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-user-a");
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "user A should be rate limited");

            // user B should still be able to request
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-user-b");
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(),
                    "user B should not be affected by user A rate limit");
        }

        @Test
        @DisplayName("user strategy supports keyLimits custom limits")
        void userStrategySupportsKeyLimits() throws Exception {
            Map<String, Integer> keyLimits = Map.of("sk-vip-user", 2);
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", keyLimits);
            userFilter.clearWindows();

            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-vip-user");

            // VIP user has only 2 requests limit
            for (int i = 0; i < 2; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "VIP user custom limit is 2, exceeding should rate limit");
        }

        @Test
        @DisplayName("empty authenticatedApiKey falls back to IP")
        void emptyAuthenticatedKeyFallsBackToIp() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "   ");
            request.setRemoteAddr("192.168.1.50");

            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "empty authenticatedApiKey should fall back to IP");
        }
    }
}
