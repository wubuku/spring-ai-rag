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

@DisplayName("RateLimitFilter — Per-user Rate Limiting")
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
    @DisplayName("Strategy: ip (default, backward compatible)")
    class IpStrategy {

        @Test
        @DisplayName("strategy=ip rates by IP")
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
                    "IP strategy should rate limit by IP even with API Key present");
        }

        @Test
        @DisplayName("strategy=ip ignores key-limits config")
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
    @DisplayName("Strategy: api-key")
    class ApiKeyStrategy {

        @Test
        @DisplayName("with API Key present, rates by key")
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
        @DisplayName("different API Keys are counted independently")
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
                    "Different API Keys should not affect each other");
        }

        @Test
        @DisplayName("no API Key present, falls back to IP rate limiting")
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
                    "No Key should fall back to IP rate limiting");
        }

        @Test
        @DisplayName("blank API Key falls back to IP")
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
    @DisplayName("Custom key-limits")
    class CustomKeyLimits {

        @Test
        @DisplayName("VIP Key uses custom limit")
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
        @DisplayName("Basic Key uses lower limit")
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
        @DisplayName("unregistered Key uses default limit")
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
                    "Rate limit header should reflect VIP limit of 50");
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
                    "Error message should contain limit 2");
        }
    }

    @Nested
    @DisplayName("Client ID Resolution")
    class ClientIdResolution {

        @Test
        @DisplayName("request attribute contains rate limit client ID")
        void requestAttributeContainsClientId() throws Exception {
            RateLimitFilter filter = createFilter("api-key", Map.of());
            request.addHeader("X-API-Key", "sk-test");

            filter.doFilterInternal(request, response, chain);

            assertEquals("sk-test", request.getAttribute(RateLimitFilter.CLIENT_ID_ATTRIBUTE));
        }

        @Test
        @DisplayName("IP strategy sets attribute to client IP")
        void ipStrategyAttributeIsIp() throws Exception {
            RateLimitFilter filter = createFilter("ip", Map.of());

            filter.doFilterInternal(request, response, chain);

            assertEquals("192.168.1.1", request.getAttribute(RateLimitFilter.CLIENT_ID_ATTRIBUTE));
        }
    }

    // ==================== Helper Method ====================

    private RateLimitFilter createFilter(String strategy, Map<String, Integer> keyLimits) {
        RateLimitFilter filter = new RateLimitFilter(true, DEFAULT_LIMIT, strategy, keyLimits);
        filter.clearWindows();
        return filter;
    }
}
