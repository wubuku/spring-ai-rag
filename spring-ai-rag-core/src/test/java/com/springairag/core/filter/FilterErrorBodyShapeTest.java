package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.security.EnvironmentRootCredentialResolver;
import com.springairag.core.service.ApiKeyManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bodies the three filters write when they refuse a request.
 *
 * <p>Batch 873. These writers sat in the same three files and disagreed with each
 * other in a way no test noticed:
 *
 * <ul>
 *   <li>{@code ApiKeyAuthFilter.sendUnauthorized} wrote the code and the sentence
 *       but no status and no path — while its third sibling,
 *       {@code sendPolicyUnavailable}, set both.</li>
 *   <li>{@code ApiKeyAuthFilter.sendServiceUnavailable} set the status and left
 *       out the path, even though {@code path} was a parameter of the method.</li>
 *   <li>{@code RateLimitFilter}'s 429 writer set the path and no status, while
 *       {@code writeStoreUnavailable} directly below it set both.</li>
 * </ul>
 *
 * <p>None of that was visible: the tests that did look at a filter body asserted
 * {@code error} and {@code message}, and both were already correct. A client
 * reading only the body could not tell what had happened.
 *
 * <p>All three filters answer {@code /v1/**} with an OpenAI-shaped body instead,
 * which these tests deliberately stay away from — that is a different vocabulary
 * for a different audience, and {@code OpenAiErrorResponse.of(...)} is the only
 * thing that should be able to produce it.
 */
class FilterErrorBodyShapeTest {

    private static final String PATH = "/api/v1/rag/documents";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Assert the five fields {@code ErrorResponse} documents, plus the two
     * backward-compatible aliases. {@code expected} says which code the body is
     * supposed to report; {@code expectedStatus} is written separately on purpose,
     * because "the body agrees with the HTTP status" and "the body agrees with the
     * catalog" are two different claims and the catalog is the one that must hold.
     */
    private void assertProblemDetail(ErrorResponse body, ErrorCode expected, int expectedStatus) {
        assertNotNull(body);
        assertEquals(expected.getCode(), body.getError());
        assertEquals(expected.getTitle(), body.getTitle());
        assertEquals(expected.getProblemTypeUri(), body.getType());
        assertEquals(expectedStatus, body.getStatus());
        assertEquals(expectedStatus, expected.getHttpStatus(),
                "the test and the catalog must agree, or this asserts nothing");
        assertEquals(PATH, body.getInstance());
        assertEquals(PATH, body.getPath());
        assertNotNull(body.getDetail());
        assertNotNull(body.getMessage());
        assertEquals(body.getDetail(), body.getMessage());
        assertNotNull(body.getTimestamp());
    }

    @Nested
    @DisplayName("ApiKeyAuthFilter")
    class Auth {

        @Test
        @DisplayName("401 body carries the status and the path it refused")
        void unauthorizedIsACompleteProblemDetail() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = mock(MockFilterChain.class);

            new ApiKeyAuthFilter("secret", true).doFilterInternal(request, response, chain);

            assertEquals(401, response.getStatus());
            verify(chain, never()).doFilter(any(), any());
            assertProblemDetail(
                    objectMapper.readValue(response.getContentAsString(), ErrorResponse.class),
                    ErrorCode.UNAUTHORIZED, 401);
        }

        @Test
        @DisplayName("an invalid key answers the same shape as a missing one")
        void invalidKeyMatchesMissingKey() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
            request.addHeader("X-API-Key", "wrong-key");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = mock(MockFilterChain.class);

            new ApiKeyAuthFilter("secret", true).doFilterInternal(request, response, chain);

            assertEquals(401, response.getStatus());
            assertProblemDetail(
                    objectMapper.readValue(response.getContentAsString(), ErrorResponse.class),
                    ErrorCode.UNAUTHORIZED, 401);
        }

        @Test
        @DisplayName("503 credential-unavailable names the request too")
        void credentialServiceUnavailableIsACompleteProblemDetail() throws Exception {
            // The sibling writer in the same class already passed `path` through;
            // this one had it as a parameter and dropped it. A body that cannot say
            // which endpoint was down is much harder to act on at 3am.
            //
            // Reached the way production reaches it — a credential store that
            // throws — rather than by reaching into the private writer, so this
            // test also covers the branch that chooses it.
            ApiKeyManagementService apiKeyService = mock(ApiKeyManagementService.class);
            when(apiKeyService.authenticate(anyString()))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                    "", true, apiKeyService,
                    new EnvironmentRootCredentialResolver("root-2026-08-14-9f4c2a7b6d1e8a3c"));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
            request.addHeader("X-API-Key", "rag_sk_corrupt");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = mock(MockFilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertEquals(503, response.getStatus());
            verify(chain, never()).doFilter(any(), any());
            assertProblemDetail(
                    objectMapper.readValue(response.getContentAsString(), ErrorResponse.class),
                    ErrorCode.CREDENTIAL_SERVICE_UNAVAILABLE, 503);
        }
    }

    @Nested
    @DisplayName("RateLimitFilter")
    class RateLimit {

        @Test
        @DisplayName("429 body carries the status the catalog declares")
        void tooManyRequestsIsACompleteProblemDetail() throws Exception {
            int limit = 2;
            RateLimitFilter filter = new RateLimitFilter(true, limit);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);

            MockHttpServletResponse response = null;
            for (int i = 0; i <= limit; i++) {
                response = new MockHttpServletResponse();
                filter.doFilterInternal(request, response, new MockFilterChain());
            }

            assertEquals(429, response.getStatus());
            assertProblemDetail(
                    objectMapper.readValue(response.getContentAsString(), ErrorResponse.class),
                    ErrorCode.TOO_MANY_REQUESTS, 429);
        }

        @Test
        @DisplayName("503 store-unavailable uses the same vocabulary")
        void storeUnavailableIsACompleteProblemDetail() throws Exception {
            // The postgresql backend with no store configured takes the
            // "cannot ask the rate limiter" branch, which is how this writer is
            // reached outside a test — driven through doFilter rather than by
            // invoking the writer directly.
            RateLimitFilter filter = new RateLimitFilter(true, 2, "ip", Map.of(), "postgresql", null);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
            request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "principal-1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = mock(MockFilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertEquals(503, response.getStatus());
            verify(chain, never()).doFilter(any(), any());
            assertProblemDetail(
                    objectMapper.readValue(response.getContentAsString(), ErrorResponse.class),
                    ErrorCode.RATE_LIMIT_STORE_UNAVAILABLE, 503);
        }
    }
}
