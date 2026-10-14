package com.springairag.core.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 从真实 Spring MVC handler mapping 构建不可变 route policy 快照。
 *
 * <p>该 registry 在应用启动时校验所有数据面 handler 都有类级或方法级 policy。它不复制
 * Controller 路径字符串，因此自定义 API version mapping 生成的最终路径也会被检查。
 */
public final class ApiRoutePolicyRegistry implements SmartInitializingSingleton {

    private static final String API_PREFIX = "/api/";
    private static final String OPENAI_PREFIX = "/v1/";
    private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

    private final Supplier<Map<RequestMappingInfo, HandlerMethod>> handlerMethodsSupplier;
    private volatile List<Route> routes = List.of();
    private volatile boolean initialized;

    public ApiRoutePolicyRegistry(RequestMappingHandlerMapping handlerMapping) {
        this(handlerMapping::getHandlerMethods);
    }

    ApiRoutePolicyRegistry(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        this(() -> handlerMethods);
    }

    public ApiRoutePolicyRegistry(
            Supplier<Map<RequestMappingInfo, HandlerMethod>> handlerMethodsSupplier) {
        this.handlerMethodsSupplier = handlerMethodsSupplier;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * 重新读取真实映射并执行完整性校验。主要供启动和 focused 测试使用。
     */
    public synchronized void validate() {
        List<Route> discovered = discover(handlerMethodsSupplier.get());
        this.routes = List.copyOf(discovered);
        this.initialized = true;
    }

    /**
     * 按 method/path 返回 route policy；没有真实映射时返回默认拒绝。
     */
    public ApiRoutePolicyDecision decision(String method, String requestUri) {
        ensureInitialized();
        if (method == null || requestUri == null) {
            return ApiRoutePolicyDecision.UNDECLARED;
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        String path = normalizePath(requestUri);
        if (!isProtectedPath(path)) {
            return ApiRoutePolicyDecision.UNDECLARED;
        }

        PathContainer container = PathContainer.parsePath(path);
        Set<ApiRoutePolicyDecision> decisions = new LinkedHashSet<>();
        for (Route route : routes) {
            if (route.matches(normalizedMethod, container)) {
                decisions.add(route.decision());
            }
        }
        if (decisions.isEmpty()) {
            return ApiRoutePolicyDecision.UNDECLARED;
        }
        if (decisions.size() > 1) {
            throw new IllegalStateException(
                    "Conflicting API route policies for " + normalizedMethod + " " + path);
        }
        return decisions.iterator().next();
    }

    /**
     * 返回已注册 route 数量，便于启动 wiring 和测试断言。
     */
    public int registeredRouteCount() {
        ensureInitialized();
        return routes.size();
    }

    /**
     * 返回脱敏、稳定排序的 route policy 摘要，不包含 Controller 实例和参数值。
     */
    public List<String> describe() {
        ensureInitialized();
        return routes.stream()
                .map(Route::description)
                .sorted()
                .toList();
    }

    private void ensureInitialized() {
        if (!initialized) {
            validate();
        }
    }

    private List<Route> discover(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        List<Route> discovered = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (!isProtectedMapping(mapping)) {
                continue;
            }
            ApiRoutePolicy policy = handler.getMethodAnnotation(ApiRoutePolicy.class);
            if (policy == null) {
                policy = handler.getBeanType().getAnnotation(ApiRoutePolicy.class);
            }
            if (policy == null) {
                throw new IllegalStateException(
                        "Missing @ApiRoutePolicy for protected handler "
                                + handler.getBeanType().getName() + "#"
                                + handler.getMethod().getName());
            }

            Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
            Set<RequestMethod> expandedMethods = methods.isEmpty()
                    ? EnumSet.allOf(RequestMethod.class)
                    : EnumSet.copyOf(methods);
            for (String pattern : mapping.getPatternValues()) {
                PathPattern pathPattern = PATH_PARSER.parse(pattern);
                for (RequestMethod requestMethod : expandedMethods) {
                    discovered.add(new Route(
                            requestMethod.name(),
                            pathPattern,
                            toDecision(policy.access(), requestMethod),
                            pattern));
                }
            }
        }
        validateConflicts(discovered);
        return discovered;
    }

    private void validateConflicts(List<Route> discovered) {
        Map<String, ApiRoutePolicyDecision> policies = new java.util.LinkedHashMap<>();
        for (Route route : discovered) {
            String key = route.method() + " " + route.pattern().getPatternString();
            ApiRoutePolicyDecision previous = policies.putIfAbsent(key, route.decision());
            if (previous != null && previous != route.decision()) {
                throw new IllegalStateException(
                        "Conflicting API route policies for " + key);
            }
        }
    }

    private boolean isProtectedMapping(RequestMappingInfo mapping) {
        return mapping.getPatternValues().stream().anyMatch(ApiRoutePolicyRegistry::isProtectedPath);
    }

    private static boolean isProtectedPath(String path) {
        return path != null && (path.startsWith(API_PREFIX) || path.startsWith(OPENAI_PREFIX));
    }

    private static ApiRoutePolicyDecision toDecision(
            ApiRoutePolicy.Access access,
            RequestMethod method) {
        return switch (access) {
            case READ -> ApiRoutePolicyDecision.READ;
            case WRITE -> ApiRoutePolicyDecision.WRITE;
            case MANAGEMENT -> ApiRoutePolicyDecision.MANAGEMENT;
            case METHOD_DEFAULT -> switch (method) {
                case GET, HEAD, OPTIONS, TRACE -> ApiRoutePolicyDecision.READ;
                default -> ApiRoutePolicyDecision.WRITE;
            };
        };
    }

    private static String normalizePath(String requestUri) {
        String path = requestUri;
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private record Route(
            String method,
            PathPattern pattern,
            ApiRoutePolicyDecision decision,
            String sourcePattern) {

        private boolean matches(String requestMethod, PathContainer path) {
            return method.equals(requestMethod) && pattern.matches(path);
        }

        private String description() {
            return method + " " + sourcePattern + " -> " + decision;
        }
    }
}
