package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiCapabilityFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void classifierAllowsReadPostsAndDefaultsUnknownMutationsToWrite() {
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "GET", "/api/v1/rag/documents"));
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "GET", "/api/v1/rag/document-sync-runs/run-id/items"));
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "GET", "/api/v1/rag/integration-observability"));
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "POST", "/api/v1/rag/chat/ask"));
        assertEquals("RAG_READ",
                ApiCapabilityFilter.requiredCapability(
                        "POST", "/api/v1/rag/search/"));
        assertEquals("RAG_WRITE",
                ApiCapabilityFilter.requiredCapability(
                        "POST", "/api/v1/rag/documents/upsert"));
        assertEquals("RAG_WRITE",
                ApiCapabilityFilter.requiredCapability(
                        "DELETE", "/api/v1/rag/chat/history/session-1"));
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", "/api/v1/rag/auth/me"));
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", "/api/v1/rag/api-keys/principals"));
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", "/api/v1/rag/alerts/active"));
        assertNull(ApiCapabilityFilter.requiredCapability(
                "GET", "/api/v1/rag/integration-capabilities"));
    }

    @Test
    void readOnlyPrincipalCanReadButCannotMutate() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter();
        FilterChainProbe chain = new FilterChainProbe();

        MockHttpServletRequest read = request("GET", "/api/v1/rag/documents");
        attachReadOnlyPrincipal(read);
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        filter.doFilterInternal(read, readResponse, chain);
        assertTrue(chain.called);

        chain.called = false;
        MockHttpServletRequest write = request("POST", "/api/v1/rag/documents/upsert");
        attachReadOnlyPrincipal(write);
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        filter.doFilterInternal(write, writeResponse, chain);

        assertFalse(chain.called);
        assertEquals(403, writeResponse.getStatus());
        ErrorResponse error = OBJECT_MAPPER.readValue(
                writeResponse.getContentAsString(), ErrorResponse.class);
        assertEquals("FORBIDDEN", error.getError());
        assertTrue(error.getMessage().contains("RAG_WRITE"));
    }

    @Test
    void readOnlyOpenAiChatEndpointIsAReadOperation() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter();
        MockHttpServletRequest request = request(
                "POST", "/v1/chat/completions");
        attachReadOnlyPrincipal(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChainProbe chain = new FilterChainProbe();
        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.called);
        assertEquals(200, response.getStatus());
    }

    @Test
    void unknownOpenAiMutationUsesStableOpenAiErrorContract() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter();
        MockHttpServletRequest request = request(
                "POST", "/v1/unknown-operation");
        attachReadOnlyPrincipal(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new FilterChainProbe());

        assertEquals(403, response.getStatus());
        OpenAiErrorResponse error = OBJECT_MAPPER.readValue(
                response.getContentAsString(), OpenAiErrorResponse.class);
        assertEquals("insufficient_permissions", error.error().code());
    }

    @Test
    void adminAndNonDatabasePrincipalsKeepCompatibilityAccess() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter();
        for (ApiKeyRole role : new ApiKeyRole[] {ApiKeyRole.ADMIN, null}) {
            MockHttpServletRequest request = request(
                    "POST", "/api/v1/rag/documents/upsert");
            if (role != null) {
                request.setAttribute(
                        ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                        "DATABASE_API_KEY");
                request.setAttribute(
                        ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                        new AuthenticatedApiPrincipal(
                                "p", "k", 1, "DATABASE_API_KEY", role,
                                null, null, 1, null));
            }
            FilterChainProbe chain = new FilterChainProbe();
            filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
            assertTrue(chain.called);
        }
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        return request;
    }

    private void attachReadOnlyPrincipal(MockHttpServletRequest request) {
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new AuthenticatedApiPrincipal(
                        "p", "k", 1,
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        ApiKeyRole.NORMAL, null, null, 1, null,
                        List.of("RAG_READ")));
    }

    private static final class FilterChainProbe
            implements jakarta.servlet.FilterChain {
        private boolean called;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            called = true;
        }
    }
}
