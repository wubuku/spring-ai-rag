package com.springairag.core.filter;

import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.ratelimit.RateLimitObservability;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresRateLimitFilterTest {

    private PostgresRateLimitStore store;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        store = mock(PostgresRateLimitStore.class);
        chain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/rag/chat");
        response = new MockHttpServletResponse();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void principalQuotaOverrideIsUsedWithoutRawCredentialFallback() throws Exception {
        authenticate("rag_k_principal", 7);
        when(store.consume("rag_k_principal", 7)).thenReturn(
                new PostgresRateLimitStore.Decision(
                        true, 3, OffsetDateTime.now(), 20));
        RateLimitFilter filter = filter();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(store).consume("rag_k_principal", 7);
        assertEquals("7", response.getHeader(RateLimitFilter.RATE_LIMIT_LIMIT_HEADER));
        assertEquals("4", response.getHeader(RateLimitFilter.RATE_LIMIT_REMAINING_HEADER));
        assertEquals(1.0, meterRegistry.get("rag.rate_limit.decisions")
                .tags("backend", "postgresql", "result", "allowed",
                        "principal_type", "DATABASE_API_KEY")
                .counter().count());
    }

    @Test
    void rejectedDecisionUsesDatabaseWindowRetryAfter() throws Exception {
        authenticate("rag_k_principal", null);
        when(store.consume("rag_k_principal", 60)).thenReturn(
                new PostgresRateLimitStore.Decision(
                        false, 60, OffsetDateTime.now(), 17));

        filter().doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(429, response.getStatus());
        assertEquals("17", response.getHeader(RateLimitFilter.RETRY_AFTER_HEADER));
        assertTrue(response.getContentAsString().contains("TOO_MANY_REQUESTS"));
    }

    @Test
    void missingAuthenticatedPrincipalFailsClosedWithoutInspectingHeaders() throws Exception {
        request.addHeader("X-API-Key", "rag_sk_must_not_be_used");

        filter().doFilterInternal(request, response, chain);

        verifyNoInteractions(store, chain);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("RATE_LIMIT_STORE_UNAVAILABLE"));
    }

    @Test
    void databaseFailureReturnsOpenAi503Envelope() throws Exception {
        request.setRequestURI("/v1/chat/completions");
        authenticate("rag_k_principal", null);
        when(store.consume("rag_k_principal", 60)).thenThrow(
                new DataAccessResourceFailureException("database unavailable"));

        filter().doFilterInternal(request, response, chain);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("rate_limit_store_unavailable"));
        verifyNoInteractions(chain);
        assertEquals(1.0, meterRegistry.get("rag.rate_limit.decisions")
                .tags("backend", "postgresql", "result", "error",
                        "principal_type", "DATABASE_API_KEY")
                .counter().count());
    }

    private RateLimitFilter filter() {
        return new RateLimitFilter(
                true, 60, "principal", Map.of(), "postgresql", store,
                new RateLimitObservability(meterRegistry));
    }

    private void authenticate(String principalId, Integer quota) {
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, principalId);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                new AuthenticatedApiPrincipal(
                        principalId, "rag_k_v1", 1,
                        ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                        ApiKeyRole.NORMAL, null, null, 1L, quota));
    }
}
