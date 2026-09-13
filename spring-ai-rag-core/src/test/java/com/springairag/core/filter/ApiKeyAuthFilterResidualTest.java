package com.springairag.core.filter;

import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyAuthFilter 残余（Batch 369）：null 根凭据解析器回退、
 * 非 Bearer/空白 Bearer 凭据拒绝、/v1/ 路径 401/503 的 OpenAI
 * 错误响应形状。
 */
class ApiKeyAuthFilterResidualTest {

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;
    private ApiKeyManagementService apiKeyService;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
        apiKeyService = mock(ApiKeyManagementService.class);
    }

    @Test
    void fourArgConstructorWithNullResolverStillAuthenticatesStaticKey()
            throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                "secret", true, apiKeyService, null);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "secret");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void nonBearerAuthorizationHeaderIsRejected() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("Authorization", "Basic abc");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Bearer scheme"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void trailingWhitespaceBearerIsTrimmedThenRejectedAsScheme() throws Exception {
        // 行为注记：normalize 先 trim——"Bearer   " 归一为 "Bearer"
        // 后不再匹配 Bearer 前缀，走 scheme 错误分支
        // （空白 Bearer 专属分支在 trim 语义下不可达，防御性保留）。
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("Authorization", "Bearer   ");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Bearer scheme"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void v1PathUnauthorizedRespondsInOpenAiErrorShape() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/v1/chat/completions");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("invalid_api_key"));
        assertTrue(body.contains("authentication_error"));
    }

    @Test
    void v1PathCredentialServiceUnavailableRespondsInOpenAiErrorShape()
            throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/v1/embeddings");
        request.addHeader("X-API-Key", "rag_sk_unavailable");
        when(apiKeyService.authenticate("rag_sk_unavailable"))
                .thenThrow(new DataAccessResourceFailureException("offline"));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(503, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("credential_service_unavailable"));
        assertTrue(body.contains("server_error"));
    }
}
