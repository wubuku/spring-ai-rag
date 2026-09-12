package com.springairag.core.filter;

import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * sendPolicyUnavailable 触发链（Batch 317）：authenticate 抛出
 * InvalidPersistedCapabilitiesException 时按路径返回 503——
 * /v1/* 走 OpenAI 错误形状（policy_service_unavailable），其余
 * 路径走 ErrorResponse（POLICY_SERVICE_UNAVAILABLE + path）。
 */
class ApiKeyAuthFilterPolicyUnavailableTest {

    private static final String ROOT_KEY =
            "root-2026-08-14-9f4c2a7b6d1e8a3c";

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    private ApiKeyAuthFilter filter() {
        ApiKeyManagementService apiKeyService =
                mock(ApiKeyManagementService.class);
        when(apiKeyService.authenticate("rag_sk_corrupt"))
                .thenThrow(new ApiCapabilitySupport
                        .InvalidPersistedCapabilitiesException(
                        "RAG_READ,RAG_WRITE,BOGUS"));
        return new ApiKeyAuthFilter(
                "",
                true,
                apiKeyService,
                new EnvironmentRootCredentialResolver(ROOT_KEY));
    }

    @Test
    void corruptedCapabilitiesReturnOpenAiStyle503OnV1Path() throws Exception {
        request.setRequestURI("/v1/chat/completions");
        request.addHeader("X-API-Key", "rag_sk_corrupt");

        filter().doFilterInternal(request, response, filterChain);

        assertEquals(503, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("policy_service_unavailable"),
                "应返回 policy_service_unavailable: " + body);
        assertTrue(body.contains("API principal policy is unavailable."));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void corruptedCapabilitiesReturnErrorResponseOnRagPath() throws Exception {
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "rag_sk_corrupt");

        filter().doFilterInternal(request, response, filterChain);

        assertEquals(503, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("POLICY_SERVICE_UNAVAILABLE"));
        assertTrue(body.contains("/api/v1/rag/documents"));
        verify(filterChain, never()).doFilter(any(), any());
    }
}
