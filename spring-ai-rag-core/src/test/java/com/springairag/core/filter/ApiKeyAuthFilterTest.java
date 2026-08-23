package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiKeyAuthFilter Unit Tests
 */
class ApiKeyAuthFilterTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
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
        when(apiKeyService.authenticate("rag_sk_dbkey"))
                .thenReturn(principal("principal-1", "kid-1"));

        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", true, apiKeyService);
        request.setRequestURI("/api/v1/rag/documents");
        request.addHeader("X-API-Key", "rag_sk_dbkey");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals("principal-1",
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE));
        assertEquals("kid-1", request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_CREDENTIAL_ID_ATTRIBUTE));
    }

    @Test
    void enabledAuth_blankStaticKey_invalidDbKey_returns401() throws ServletException, IOException {
        ApiKeyManagementService apiKeyService = mock(ApiKeyManagementService.class);
        when(apiKeyService.authenticate("bad")).thenReturn(null);

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

    @Test
    void rootMode_authDisabledStillRequiresCredential()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = rootFilter("legacy-static", false, null);
        request.setRequestURI("/api/v1/rag/documents");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void rootMode_bearerRootSetsStablePrincipal()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = rootFilter("", false, null);
        request.setRequestURI("/api/v1/rag/auth/me");
        request.addHeader("Authorization", "Bearer " + ROOT_KEY);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(EnvironmentRootCredentialResolver.PRINCIPAL_ID,
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE));
        assertEquals(ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT,
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE));
        assertEquals(Boolean.TRUE,
                request.getAttribute(ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE));
    }

    @Test
    void rootMode_databaseBusinessKeyPassesWithoutRootMarker()
            throws ServletException, IOException {
        ApiKeyManagementService service = mock(ApiKeyManagementService.class);
        when(service.authenticate("rag_sk_business")).thenReturn(
                principal("rag_k_business", "rag_k_business_v1"));
        ApiKeyAuthFilter filter = rootFilter("", false, service);
        request.setRequestURI("/api/v1/rag/search");
        request.addHeader("X-API-Key", "rag_sk_business");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals("rag_k_business",
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE));
        assertNull(request.getAttribute(
                ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE));
    }

    @Test
    void rootMode_rejectsQueryCredential()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = rootFilter("", false, null);
        request.setRequestURI("/api/v1/rag/chat/stream");
        request.setParameter("apiKey", ROOT_KEY);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Query-string"));
    }

    @Test
    void rootMode_rejectsConflictingHeaders()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = rootFilter("", false, null);
        request.setRequestURI("/api/v1/rag/search");
        request.addHeader("X-API-Key", ROOT_KEY);
        request.addHeader("Authorization", "Bearer different-credential-value");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Conflicting"));
    }

    @Test
    void rootMode_doesNotAcceptLegacyStaticFallback()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = rootFilter("legacy-static", false, null);
        request.setRequestURI("/api/v1/rag/search");
        request.addHeader("X-API-Key", "legacy-static");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
    }

    @Test
    void rootMode_cacheStatsRequiresAuthentication()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = rootFilter("", false, null);
        request.setRequestURI("/api/v1/rag/cache/stats");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
    }

    @Test
    void rootMode_databaseFailureReturns503WithoutStaticFallback()
            throws ServletException, IOException {
        ApiKeyManagementService service = mock(ApiKeyManagementService.class);
        when(service.authenticate("rag_sk_candidate"))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));
        ApiKeyAuthFilter filter = rootFilter("rag_sk_candidate", false, service);
        request.setRequestURI("/api/v1/rag/search");
        request.addHeader("X-API-Key", "rag_sk_candidate");

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("CREDENTIAL_SERVICE_UNAVAILABLE"));
    }

    @Test
    void legacyMode_queryCredentialRemainsSupported()
            throws ServletException, IOException {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("legacy-static", true);
        request.setRequestURI("/api/v1/rag/chat/stream");
        request.setParameter("apiKey", "legacy-static");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC,
                request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE));
    }

    private ApiKeyAuthFilter rootFilter(
            String legacyKey, boolean enabled, ApiKeyManagementService service) {
        return new ApiKeyAuthFilter(
                legacyKey,
                enabled,
                service,
                new EnvironmentRootCredentialResolver(ROOT_KEY));
    }

    private AuthenticatedApiPrincipal principal(
            String principalId, String credentialId) {
        return new AuthenticatedApiPrincipal(
                principalId,
                credentialId,
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                null,
                null,
                1L,
                null);
    }
}
