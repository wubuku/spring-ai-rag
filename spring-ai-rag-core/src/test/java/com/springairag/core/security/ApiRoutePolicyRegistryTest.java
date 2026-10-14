package com.springairag.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiRoutePolicyRegistryTest {

    @Test
    void classPolicyUsesHttpMethodAndMethodPolicyOverridesReadPost() throws Exception {
        ApiRoutePolicyRegistry registry = registry(
                mapping("/api/v1/rag/items", RequestMethod.GET,
                        PolicyController.class, "get"),
                mapping("/api/v1/rag/items", RequestMethod.POST,
                        PolicyController.class, "post"),
                mapping("/api/v1/rag/search", RequestMethod.POST,
                        PolicyController.class, "search"));

        assertEquals(ApiRoutePolicyDecision.READ,
                registry.decision("GET", "/api/v1/rag/items?size=10"));
        assertEquals(ApiRoutePolicyDecision.WRITE,
                registry.decision("POST", "/api/v1/rag/items"));
        assertEquals(ApiRoutePolicyDecision.READ,
                registry.decision("POST", "/api/v1/rag/search/"));
    }

    @Test
    void managementPolicyIsNotConvertedToReadOrWrite() throws Exception {
        ApiRoutePolicyRegistry registry = registry(
                mapping("/api/v1/rag/admin", RequestMethod.GET,
                        ManagementController.class, "get"));

        assertEquals(ApiRoutePolicyDecision.MANAGEMENT,
                registry.decision("GET", "/api/v1/rag/admin"));
    }

    @Test
    void missingPolicyFailsValidation() throws Exception {
        ApiRoutePolicyRegistry registry = rawRegistry(
                mapping("/api/v1/rag/missing", RequestMethod.GET,
                        MissingPolicyController.class, "get"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::validate);
        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("Missing @ApiRoutePolicy"));
    }

    @Test
    void conflictingPoliciesFailValidation() throws Exception {
        ApiRoutePolicyRegistry registry = rawRegistry(
                mapping("/api/v1/rag/conflict", RequestMethod.GET,
                        PolicyController.class, "get"),
                mapping("/api/v1/rag/conflict", RequestMethod.GET,
                        WriteController.class, "get", "variant=two"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, registry::validate);
        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("Conflicting API route policies"));
    }

    @Test
    void unknownAndNonApiPathsAreUndeclared() throws Exception {
        ApiRoutePolicyRegistry registry = registry(
                mapping("/api/v1/rag/items", RequestMethod.GET,
                        PolicyController.class, "get"));

        assertEquals(ApiRoutePolicyDecision.UNDECLARED,
                registry.decision("GET", "/api/v1/rag/unknown"));
        assertEquals(ApiRoutePolicyDecision.UNDECLARED,
                registry.decision("GET", "/actuator/health"));
    }

    private ApiRoutePolicyRegistry registry(RegisteredMapping... mappings) {
        ApiRoutePolicyRegistry registry = rawRegistry(mappings);
        registry.validate();
        return registry;
    }

    private ApiRoutePolicyRegistry rawRegistry(RegisteredMapping... mappings) {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = new LinkedHashMap<>();
        for (RegisteredMapping registered : mappings) {
            handlerMethods.put(registered.info(), registered.handler());
        }
        return new ApiRoutePolicyRegistry(handlerMethods);
    }

    private RegisteredMapping mapping(
            String path,
            RequestMethod method,
            Class<?> controller,
            String handlerName) throws Exception {
        return mapping(path, method, controller, handlerName, null);
    }

    private RegisteredMapping mapping(
            String path,
            RequestMethod method,
            Class<?> controller,
            String handlerName,
            String param) throws Exception {
        RequestMappingInfo.Builder builder = RequestMappingInfo.paths(path).methods(method);
        if (param != null) {
            builder.params(param);
        }
        Method handler = controller.getMethod(handlerName);
        return new RegisteredMapping(
                builder.build(),
                new HandlerMethod(controller.getDeclaredConstructor().newInstance(), handler));
    }

    private record RegisteredMapping(RequestMappingInfo info, HandlerMethod handler) {
    }

    @RestController
    @RequestMapping
    @ApiRoutePolicy
    static class PolicyController {
        @GetMapping
        public String get() { return "get"; }

        @PostMapping
        public String post() { return "post"; }

        @ApiRoutePolicy(access = ApiRoutePolicy.Access.READ)
        @PostMapping
        public String search() { return "search"; }
    }

    @RestController
    @ApiRoutePolicy(access = ApiRoutePolicy.Access.MANAGEMENT)
    static class ManagementController {
        public String get() { return "get"; }
    }

    @RestController
    static class MissingPolicyController {
        public String get() { return "get"; }
    }

    @RestController
    @ApiRoutePolicy(access = ApiRoutePolicy.Access.WRITE)
    static class WriteController {
        public String get() { return "get"; }
    }
}
