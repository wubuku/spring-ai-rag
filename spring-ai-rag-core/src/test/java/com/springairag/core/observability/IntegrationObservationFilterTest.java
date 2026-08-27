package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IntegrationObservationFilterTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 403, 404, 409, 429, 503})
    void recordsFinalHttpStatusWithStablePrincipalAndCollections(int status)
            throws Exception {
        RagProperties properties = new RagProperties();
        IntegrationObservationRecorder recorder =
                mock(IntegrationObservationRecorder.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationFilter filter = new IntegrationObservationFilter(
                recorder,
                properties,
                registry);
        MockHttpServletRequest request = request();
        bindDatabasePrincipal(request);
        IntegrationObservationContext.addAuthorizedCollection(request, 9L);
        IntegrationObservationContext.addAuthorizedCollection(request, 2L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) ->
                response.setStatus(status);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<IntegrationObservation> observation =
                ArgumentCaptor.forClass(IntegrationObservation.class);
        verify(recorder).record(observation.capture());
        assertEquals(status, observation.getValue().httpStatus());
        assertEquals("principal-1", observation.getValue().principalRef());
        assertEquals(List.of(2L, 9L),
                observation.getValue().authorizedCollectionIds());
        assertEquals(IntegrationOperation.JSON_RECORD_SEARCH,
                observation.getValue().operation());
        assertEquals(1.0,
                registry.get("rag.integration.requests")
                        .tag("operation", "JSON_RECORD_SEARCH")
                        .tag(
                                "status_class",
                                IntegrationHttpStatusClass.from(status).name())
                        .tag("principal_type", "DATABASE_API_KEY")
                        .counter()
                        .count());
    }

    @Test
    void exceptionPathRecords500AndPropagatesOriginalException() throws Exception {
        RagProperties properties = new RagProperties();
        IntegrationObservationRecorder recorder =
                mock(IntegrationObservationRecorder.class);
        IntegrationObservationFilter filter = new IntegrationObservationFilter(
                recorder,
                properties,
                new SimpleMeterRegistry());
        MockHttpServletRequest request = request();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE,
                true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException expected = new ServletException("controller failure");
        FilterChain chain = (ignoredRequest, ignoredResponse) -> {
            throw expected;
        };

        ServletException actual = assertThrows(
                ServletException.class,
                () -> filter.doFilter(request, response, chain));

        assertSame(expected, actual);
        ArgumentCaptor<IntegrationObservation> observation =
                ArgumentCaptor.forClass(IntegrationObservation.class);
        verify(recorder).record(observation.capture());
        assertEquals(500, observation.getValue().httpStatus());
        assertEquals("ANONYMOUS", observation.getValue().principalType());
    }

    @Test
    void recorderFailureDoesNotChangeCompletedBusinessResponse() throws Exception {
        RagProperties properties = new RagProperties();
        IntegrationObservationRecorder recorder =
                mock(IntegrationObservationRecorder.class);
        doThrow(new IllegalStateException("recorder failed"))
                .when(recorder)
                .record(any());
        IntegrationObservationFilter filter = new IntegrationObservationFilter(
                recorder,
                properties,
                new SimpleMeterRegistry());
        MockHttpServletRequest request = request();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE,
                false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> {
            response.setStatus(202);
            response.getWriter().write("accepted");
        };

        filter.doFilter(request, response, chain);

        assertEquals(202, response.getStatus());
        assertEquals("accepted", response.getContentAsString());
    }

    @Test
    void unknownRouteAndDisabledRecorderAreTransparent() throws Exception {
        RagProperties properties = new RagProperties();
        IntegrationObservationRecorder recorder =
                mock(IntegrationObservationRecorder.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationFilter filter = new IntegrationObservationFilter(
                recorder,
                properties,
                registry);
        MockHttpServletRequest unknown = new MockHttpServletRequest(
                "GET",
                "/api/v1/rag/unknown");
        MockHttpServletResponse unknownResponse = new MockHttpServletResponse();

        filter.doFilter(
                unknown,
                unknownResponse,
                (ignoredRequest, ignoredResponse) ->
                        unknownResponse.setStatus(204));

        verify(recorder, never()).record(any());
        assertEquals(204, unknownResponse.getStatus());
        assertEquals(0, registry.getMeters().size());

        properties.getIntegrationObservability().setEnabled(false);
        MockHttpServletResponse disabledResponse = new MockHttpServletResponse();
        filter.doFilter(
                request(),
                disabledResponse,
                (ignoredRequest, ignoredResponse) ->
                        disabledResponse.setStatus(201));
        verify(recorder, never()).record(any());
        assertEquals(201, disabledResponse.getStatus());
        assertEquals(0, registry.getMeters().size());
    }

    @Test
    void meterTagsContainOnlyFixedLowCardinalityKeys() throws Exception {
        RagProperties properties = new RagProperties();
        IntegrationObservationRecorder recorder =
                mock(IntegrationObservationRecorder.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationObservationFilter filter = new IntegrationObservationFilter(
                recorder,
                properties,
                registry);
        MockHttpServletRequest request = request();
        bindDatabasePrincipal(request);
        IntegrationObservationContext.addAuthorizedCollection(request, 123L);
        request.setQueryString("externalId=private-value");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                });

        Set<String> allowed = Set.of(
                "operation",
                "status_class",
                "principal_type");
        for (Meter meter : registry.getMeters()) {
            assertEquals(
                    allowed,
                    meter.getId().getTags().stream()
                            .map(tag -> tag.getKey())
                            .collect(Collectors.toSet()));
            List<String> values = meter.getId().getTags().stream()
                    .map(tag -> tag.getValue())
                    .toList();
            assertEquals(false, values.contains("principal-1"));
            assertEquals(false, values.contains("123"));
            assertEquals(false, values.contains("private-value"));
            assertEquals(false, values.contains(request.getRequestURI()));
        }
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest(
                "POST",
                "/api/v1/rag/json-records/search");
    }

    private void bindDatabasePrincipal(MockHttpServletRequest request) {
        AuthenticatedApiPrincipal principal = new AuthenticatedApiPrincipal(
                "principal-1",
                "credential-1",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                ApiKeyRole.NORMAL,
                null,
                null,
                1L,
                null);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                principal.getPrincipalId());
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal);
    }
}
