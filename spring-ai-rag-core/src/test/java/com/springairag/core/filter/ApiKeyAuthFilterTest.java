package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.service.ApiKeyManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiKeyAuthFilter Unit Tests
 */
class ApiKeyAuthFilterTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void disabledAuth_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", false);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void enabledAuth_blankStaticKey_missingRequestKey_returns401() throws ServletException, IOException {
        // Security enabled with empty static key must still require a key (DB keys only mode)
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(401, response.getStatus());
    }

    @Test
    void enabledAuth_nullStaticKey_missingRequestKey_returns401() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(null, true);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(401, response.getStatus());
    }

    @Test
    void enabledAuth_blankStaticKey_validDbKey_passes() throws ServletException, IOException {
        ApiKeyManagementService apiKeyService = mock(ApiKeyManagementService.class);
        RagApiKey entity = new RagApiKey();
        entity.setKeyId("kid-1");
        when(apiKeyService.validateKeyEntity("rag_sk_dbkey")).thenReturn(entity);

        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "rag_sk_dbkey");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals("kid-1", request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE));
    }

    @Test
    void enabledAuth_blankStaticKey_invalidDbKey_returns401() throws ServletException, IOException {
        ApiKeyManagementService apiKeyService = mock(ApiKeyManagementService.class);
        when(apiKeyService.validateKeyEntity("bad")).thenReturn(null);

        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "bad");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(401, response.getStatus());
    }

    @Test
    void excludedPath_actuator_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/actuator/health");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void excludedPath_swagger_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/swagger-ui/index.html");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void excludedPath_apiDocs_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/v3/api-docs");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void missingApiKey_returns401() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse error = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals("UNAUTHORIZED", error.getError());
        assertTrue(error.getMessage().contains("Missing API Key"));
    }

    @Test
    void invalidApiKey_returns401() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "wrong-key");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse error = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals("UNAUTHORIZED", error.getError());
        assertTrue(error.getMessage().contains("Invalid API Key"));
    }

    @Test
    void validApiKey_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("my-secret-key", true);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "my-secret-key");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void validApiKey_withDifferentPath_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("key123", true);
        request.setRequestURI("/api/v1/rag/chat/ask");
        request.addHeader("X-API-Key", "key123");
        request.setMethod("POST");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void blankApiKeyHeader_returns401() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "  ");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void excludedPath_cacheStats_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/api/v1/rag/cache/stats");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void cacheInvalidate_requiresAuth_returns401() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/api/v1/rag/cache/invalidate");
        request.setMethod("DELETE");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse error = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals("UNAUTHORIZED", error.getError());
        assertTrue(error.getMessage().contains("Missing API Key"));
    }

    @Test
    void cacheInvalidate_withValidKey_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", true);
        request.setRequestURI("/api/v1/rag/cache/invalidate");
        request.setMethod("DELETE");
        request.addHeader("X-API-Key", "secret");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }
}
