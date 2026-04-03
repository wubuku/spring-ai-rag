package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimitFilter — Per-user 限流增强")
class RateLimitFilterPerUserTest {

    private static final int DEFAULT_LIMIT = 10;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        request.setRequestURI("/api/v1/rag/chat");
        request.setRemoteAddr("192.168.1.1");
    }

    @Nested
    @DisplayName("策略：ip（默认，向后兼容）")
    class IpStrategy {

        @Test
        @DisplayName("strategy=ip 时按 IP 限流")
        void ipStrategyUsesIp() throws Exception {
            RateLimitFilter filter = createFilter("ip", Map.of());
            request.addHeader("X-API-Key", "sk-test");

            for (int i = 0; i < DEFAULT_LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);

            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "IP 策略下即使有 API Key 也应按 IP 限流");
        }

        @Test
        @DisplayName("strategy=ip 时忽略 key-limits 配置")
        void ipStrategyIgnoresKeyLimits() throws Exception {
            RateLimitFilter filter = createFilter("ip", Map.of("sk-vip", 100));
            request.addHeader("X-API-Key", "sk-vip");

            for (int i = 0; i < DEFAULT_LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        }
    }

    @Nested
    @DisplayName("策略：api-key")
    class ApiKeyStrategy {

        @Test
        @DisplayName("携带 API Key 时按 Key 限流")
        void apiKeyStrategyUsesKey() throws Exception {
            RateLimitFilter filter = createFilter("api-key", Map.of());
            request.addHeader("X-API-Key", "sk-user-1");

            for (int i = 0; i < DEFAULT_LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        }

        @Test
        @DisplayName("不同 API Key 各自独立计数")
        void differentApiKeysIndependent() throws Exception {
            RateLimitFilter filter = createFilter("api-key", Map.of());

            request.addHeader("X-API-Key", "sk-key-1");
            for (int i = 0; i < DEFAULT_LIMIT + 1; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());

            MockHttpServletRequest req2 = new MockHttpServletRequest();
            req2.setRequestURI("/api/v1/rag/chat");
            req2.setRemoteAddr("192.168.1.1");
            req2.addHeader("X-API-Key", "sk-key-2");
            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(req2, response, chain);
            assertEquals(HttpStatus.OK.value(), response.getStatus(),
                    "不同 API Key 不应互相影响");
        }

        @Test
        @DisplayName("未携带 API Key 时回退到 IP 限流")
        void fallsBackToIpWhenNoKey() throws Exception {
            RateLimitFilter filter = createFilter("api-key", Map.of());

            for (int i = 0; i < DEFAULT_LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus(),
                    "无 Key 时应按 IP 限流");
        }

        @Test
        @DisplayName("API Key 空白时回退到 IP")
        void fallsBackToIpWhenKeyBlank() throws Exception {
            RateLimitFilter filter = createFilter("api-key", Map.of());
            request.addHeader("X-API-Key", "  ");

            for (int i = 0; i < DEFAULT_LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        }
    }

    @Nested
    @DisplayName("自定义限额（key-limits）")
    class CustomKeyLimits {

        @Test
        @DisplayName("VIP Key 使用自定义限额")
        void vipKeyUsesCustomLimit() throws Exception {
            RateLimitFilter filter = createFilter("api-key",
                    Map.of("sk-vip", 50, "sk-basic", 5));
            request.addHeader("X-API-Key", "sk-vip");

            for (int i = 0; i < 50; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        }

        @Test
        @DisplayName("Basic Key 使用较低限额")
        void basicKeyUsesLowerLimit() throws Exception {
            RateLimitFilter filter = createFilter("api-key",
                    Map.of("sk-vip", 50, "sk-basic", 5));
            request.addHeader("X-API-Key", "sk-basic");

            for (int i = 0; i < 5; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        }

        @Test
        @DisplayName("未注册 Key 使用默认限额")
        void unknownKeyUsesDefaultLimit() throws Exception {
            RateLimitFilter filter = createFilter("api-key",
                    Map.of("sk-vip", 50));
            request.addHeader("X-API-Key", "sk-unknown");

            for (int i = 0; i < DEFAULT_LIMIT; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }
            assertEquals(HttpStatus.OK.value(), response.getStatus());

            response = new MockHttpServletResponse();
            chain = new MockFilterChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        }

        @Test
        @DisplayName("限额响应头反映自定义限额")
        void responseHeadersReflectCustomLimit() throws Exception {
            RateLimitFilter filter = createFilter("api-key",
                    Map.of("sk-vip", 50));
            request.addHeader("X-API-Key", "sk-vip");

            filter.doFilterInternal(request, response, chain);

            assertEquals("50", response.getHeader("X-RateLimit-Limit"),
                    "限额响应头应反映 VIP 的 50 次限额");
            assertEquals("49", response.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        @DisplayName("429 响应消息包含自定义限额")
        void rateLimitMessageShowsCustomLimit() throws Exception {
            RateLimitFilter filter = createFilter("api-key",
                    Map.of("sk-basic", 2));
            request.addHeader("X-API-Key", "sk-basic");

            for (int i = 0; i < 3; i++) {
                response = new MockHttpServletResponse();
                chain = new MockFilterChain();
                filter.doFilterInternal(request, response, chain);
            }

            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
            var body = objectMapper.readTree(response.getContentAsString());
            assertTrue(body.get("message").asText().contains("2"),
                    "错误消息应包含限额 2");
        }
    }

    @Nested
    @DisplayName("标识符解析")
    class ClientIdResolution {

        @Test
        @DisplayName("请求属性包含限流标识符")
        void requestAttributeContainsClientId() throws Exception {
            RateLimitFilter filter = createFilter("api-key", Map.of());
            request.addHeader("X-API-Key", "sk-test");

            filter.doFilterInternal(request, response, chain);

            assertEquals("sk-test", request.getAttribute(RateLimitFilter.CLIENT_ID_ATTRIBUTE));
        }

        @Test
        @DisplayName("IP 策略时属性为 IP")
        void ipStrategyAttributeIsIp() throws Exception {
            RateLimitFilter filter = createFilter("ip", Map.of());

            filter.doFilterInternal(request, response, chain);

            assertEquals("192.168.1.1", request.getAttribute(RateLimitFilter.CLIENT_ID_ATTRIBUTE));
        }
    }

    // ==================== 辅助方法 ====================

    private RateLimitFilter createFilter(String strategy, Map<String, Integer> keyLimits) {
        RateLimitFilter filter = new RateLimitFilter(true, DEFAULT_LIMIT, strategy, keyLimits);
        filter.clearWindows();
        return filter;
    }
}
