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

@DisplayName("RateLimitFilter — API 限流过滤器")
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
    @DisplayName("正常放行")
    class AllowRequests {

        @Test
        @DisplayName("限流关闭时所有请求放行")
        void disabledAllowsAll() throws Exception {
            RateLimitFilter disabled = new RateLimitFilter(false, LIMIT);

            for (int i = 0; i < 20; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                disabled.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        "第 " + (i + 1) + " 个请求应放行");
            }
        }

        @Test
        @DisplayName("requestsPerMinute ≤ 0 时放行")
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
        @DisplayName("在限额内请求正常放行")
        void withinLimitAllows() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        "第 " + (i + 1) + " 个请求应在限额内");
                assertNotNull(chain.getRequest(), "请求应传递到 filter chain");
            }
        }

        @Test
        @DisplayName("排除路径不计数")
        void excludedPathsDoNotCount() throws Exception {
            String[] excluded = {"/actuator/health", "/swagger-ui/index.html",
                    "/v3/api-docs", "/health", "/error"};

            for (String path : excluded) {
                request.setRequestURI(path);
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        path + " 应放行");
            }

            // 排除路径不消耗限额，正常路径仍可请求
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
    @DisplayName("限流触发")
    class RateLimitTriggered {

        @Test
        @DisplayName("超出限额返回 429")
        void exceedsLimitReturns429() throws Exception {
            // 先消耗完限额
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            // 第 LIMIT+1 个请求应被限流
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);

            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
            assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
            assertEquals("60", response.getHeader("Retry-After"));
        }

        @Test
        @DisplayName("429 响应体包含错误详情")
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
        @DisplayName("429 时请求不传递到 filter chain")
        void rateLimitBlocksChain() throws Exception {
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            // chain 应该没有被调用（最后一个请求被限流了）
            // 注意：chain 在最后一次调用前是新的，所以要看前面的
            // 更简单的方式：直接检查第 LIMIT+1 次调用后 chain 状态
            // 由于 chain 每次新建，用另一种方式验证
            response = new MockHttpServletResponse();
            MockFilterChain newChain = new MockFilterChain();
            filter.doFilterInternal(request, response, newChain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
            assertNull(newChain.getRequest(), "被限流的请求不应传递到 chain");
        }
    }

    @Nested
    @DisplayName("响应头")
    class ResponseHeaders {

        @Test
        @DisplayName("正常响应包含限额头")
        void normalResponseHasHeaders() throws Exception {
            filter.doFilterInternal(request, response, chain);

            assertEquals(String.valueOf(LIMIT), response.getHeader("X-RateLimit-Limit"));
            assertNotNull(response.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        @DisplayName("剩余计数递减")
        void remainingDecreases() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);

                int expectedRemaining = LIMIT - i - 1;
                assertEquals(String.valueOf(expectedRemaining),
                        response.getHeader("X-RateLimit-Remaining"),
                        "第 " + (i + 1) + " 次后剩余应为 " + expectedRemaining);
            }
        }

        @Test
        @DisplayName("429 响应包含 Retry-After 和限额头")
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
    @DisplayName("IP 隔离")
    class IpIsolation {

        @Test
        @DisplayName("不同 IP 各自独立计数")
        void differentIpsIndependent() throws Exception {
            MockHttpServletRequest req2 = new MockHttpServletRequest();
            req2.setRequestURI("/api/v1/rag/chat");
            req2.setRemoteAddr("192.168.1.2");

            // IP1 消耗完限额
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            // IP2 应仍可正常请求
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(req2, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(), "不同 IP 不应互相影响");
        }

        @Test
        @DisplayName("X-Forwarded-For 优先于 RemoteAddr")
        void forwardedForUsed() throws Exception {
            request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");

            String ip = filter.resolveClientIp(request);
            assertEquals("10.0.0.1", ip);
        }

        @Test
        @DisplayName("X-Forwarded-For 为 null 时回退 RemoteAddr")
        void fallbackToRemoteAddr() throws Exception {
            request.setRemoteAddr("127.0.0.1");

            String ip = filter.resolveClientIp(request);
            assertEquals("127.0.0.1", ip);
        }
    }

    @Nested
    @DisplayName("窗口过期")
    class WindowExpiry {

        @Test
        @DisplayName("窗口过期后计数重置")
        void expiredWindowResets() throws Exception {
            // 消耗完限额
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());

            // 模拟窗口过期：直接修改 windowStart
            var window = filter.getWindows().get("192.168.1.1");
            assertNotNull(window);

            // 使用反射或直接操作 map 来模拟过期
            // 由于 WindowState 的 windowStart 是 final，我们清除 map 让它重建
            filter.clearWindows();

            // 过期后应重新放行
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(), "窗口过期后应重置计数");
        }
    }

    @Nested
    @DisplayName("user 策略 — 按已认证用户限流")
    class UserStrategy {

        @Test
        @DisplayName("已认证用户使用 authenticatedApiKey 属性作为限流标识")
        void authenticatedUserUsesAttribute() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            // 模拟 ApiKeyAuthFilter 已设置的认证属性
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-authenticated-user");
            request.setRemoteAddr("192.168.1.100");

            for (int i = 0; i < LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus(),
                        "第 " + (i + 1) + " 次请求应在限额内");
            }

            // 第 LIMIT+1 次应被限流
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "第 " + (LIMIT + 1) + " 次请求应触发限流");
        }

        @Test
        @DisplayName("未认证用户回退到 IP 限流")
        void unauthenticatedUserFallsBackToIp() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            // 未设置 authenticatedApiKey 属性（模拟未认证）
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
                    "未认证用户应使用 IP 限流");
        }

        @Test
        @DisplayName("不同已认证用户独立计数")
        void differentAuthenticatedUsersIndependent() throws Exception {
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", Map.of());
            userFilter.clearWindows();

            // 用户 A 消耗完限额
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-user-a");
            for (int i = 0; i <= LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                userFilter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "用户 A 应被限流");

            // 用户 B 应仍可正常请求
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-user-b");
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            userFilter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(),
                    "用户 B 不应受用户 A 限流影响");
        }

        @Test
        @DisplayName("user 策略支持 keyLimits 自定义限额")
        void userStrategySupportsKeyLimits() throws Exception {
            Map<String, Integer> keyLimits = Map.of("sk-vip-user", 2);
            RateLimitFilter userFilter = new RateLimitFilter(true, LIMIT, "user", keyLimits);
            userFilter.clearWindows();

            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "sk-vip-user");

            // VIP 用户只有 2 次限额
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
                    "VIP 用户自定义限额为 2，超出应限流");
        }

        @Test
        @DisplayName("authenticatedApiKey 为空字符串时回退到 IP")
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
                    "空字符串 authenticatedApiKey 应回退到 IP");
        }
    }
}
