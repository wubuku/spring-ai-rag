package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.ApiRoutePolicy;
import com.springairag.core.security.ApiRoutePolicyRegistry;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiCapabilityFilterRoutePolicyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void undeclaredDataPlaneRouteDefaultsToForbidden() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter(registry(
                "/api/v1/rag/documents", RequestMethod.GET,
                DeclaredController.class, "get"));
        MockHttpServletRequest request = request("GET", "/api/v1/rag/not-registered");
        attachReadOnlyPrincipal(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChainProbe chain = new FilterChainProbe();

        filter.doFilterInternal(request, response, chain);

        assertFalse(chain.called);
        assertEquals(403, response.getStatus());
        ErrorResponse error = OBJECT_MAPPER.readValue(
                response.getContentAsString(), ErrorResponse.class);
        assertEquals("ROUTE_NOT_DECLARED", error.getError());
    }

    @Test
    void undeclaredOpenAiRouteUsesOpenAiErrorContract() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter(registry(
                "/v1/chat/completions", RequestMethod.POST,
                ReadController.class, "chat"));
        MockHttpServletRequest request = request("POST", "/v1/unknown-operation");
        attachReadOnlyPrincipal(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new FilterChainProbe());

        assertEquals(403, response.getStatus());
        OpenAiErrorResponse error = OBJECT_MAPPER.readValue(
                response.getContentAsString(), OpenAiErrorResponse.class);
        assertEquals("ROUTE_NOT_DECLARED", error.error().code());
    }

    @Test
    void declaredWriteRouteStillRequiresWriteCapability() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter(registry(
                "/api/v1/rag/documents", RequestMethod.POST,
                DeclaredController.class, "write"));
        MockHttpServletRequest request = request("POST", "/api/v1/rag/documents");
        attachReadOnlyPrincipal(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChainProbe chain = new FilterChainProbe();

        filter.doFilterInternal(request, response, chain);

        assertFalse(chain.called);
        assertEquals(403, response.getStatus());
        ErrorResponse error = OBJECT_MAPPER.readValue(
                response.getContentAsString(), ErrorResponse.class);
        assertEquals("FORBIDDEN", error.getError());
        assertTrue(error.getMessage().contains("RAG_WRITE"));
    }

    @Test
    void managementRouteRemainsOutsideCapabilityGate() throws Exception {
        ApiCapabilityFilter filter = new ApiCapabilityFilter(registry(
                "/api/v1/rag/admin", RequestMethod.GET,
                ManagementController.class, "get"));
        MockHttpServletRequest request = request("GET", "/api/v1/rag/admin");
        attachReadOnlyPrincipal(request);
        FilterChainProbe chain = new FilterChainProbe();

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertTrue(chain.called);
    }

    private ApiRoutePolicyRegistry registry(
            String path,
            RequestMethod method,
            Class<?> controller,
            String handlerName) throws Exception {
        RequestMappingInfo info = RequestMappingInfo.paths(path).methods(method).build();
        HandlerMethod handler = new HandlerMethod(
                controller.getDeclaredConstructor().newInstance(),
                controller.getMethod(handlerName));
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(info, handler);
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(mappings);
        ApiRoutePolicyRegistry registry = new ApiRoutePolicyRegistry(mapping);
        registry.validate();
        return registry;
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
                        java.util.List.of("RAG_READ")));
    }

    private static final class FilterChainProbe implements jakarta.servlet.FilterChain {
        private boolean called;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            called = true;
        }
    }

    @ApiRoutePolicy
    static class DeclaredController {
        public String get() { return "get"; }

        public String write() { return "write"; }
    }

    @ApiRoutePolicy(access = ApiRoutePolicy.Access.READ)
    static class ReadController {
        public String chat() { return "chat"; }
    }

    @ApiRoutePolicy(access = ApiRoutePolicy.Access.MANAGEMENT)
    static class ManagementController {
        public String get() { return "get"; }
    }
}
