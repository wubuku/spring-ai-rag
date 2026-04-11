package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
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
 * ApiKeyAuthFilter 单元测试
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
    void blankApiKey_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void nullApiKey_passesThrough() throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(null, true);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
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
