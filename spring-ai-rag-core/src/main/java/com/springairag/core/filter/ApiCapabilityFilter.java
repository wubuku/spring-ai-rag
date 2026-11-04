package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.security.ApiRoutePolicyDecision;
import com.springairag.core.security.ApiRoutePolicyRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Enforces operation capabilities after authentication and before rate limiting.
 */
public class ApiCapabilityFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> READ_POST_PATHS = Set.of(
            "/api/v1/rag/search",
            "/api/v1/rag/json-records/search",
            "/api/v1/rag/chat",
            "/api/v1/rag/chat/ask",
            "/api/v1/rag/chat/stream",
            "/api/v1/rag/models/compare",
            "/api/v1/rag/evaluation/answer-quality",
            "/api/v1/rag/evaluation/semantic",
            "/api/v1/rag/evaluation/semantic/batch",
            "/v1/chat/completions");

    private final ApiRoutePolicyRegistry routePolicyRegistry;

    public ApiCapabilityFilter() {
        this(null);
    }

    public ApiCapabilityFilter(ApiRoutePolicyRegistry routePolicyRegistry) {
        this.routePolicyRegistry = routePolicyRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Object snapshot = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        Object principalType = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (!(snapshot instanceof AuthenticatedApiPrincipal principal)
                || !ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(principalType)
                || principal.getRole() == ApiKeyRole.ADMIN) {
            filterChain.doFilter(request, response);
            return;
        }

        ApiRoutePolicyDecision decision = routePolicyRegistry == null
                ? legacyDecision(request)
                : routePolicyRegistry.decision(
                        request.getMethod(), request.getRequestURI());
        if (decision == ApiRoutePolicyDecision.UNDECLARED
                && isManagementOrIdentityPath(normalizePath(request.getRequestURI()))) {
            decision = ApiRoutePolicyDecision.MANAGEMENT;
        }
        if (decision == ApiRoutePolicyDecision.MANAGEMENT) {
            filterChain.doFilter(request, response);
            return;
        }
        if (decision == ApiRoutePolicyDecision.UNDECLARED) {
            sendRouteNotDeclared(request, response, request.getRequestURI());
            return;
        }
        String required = decision == ApiRoutePolicyDecision.READ
                ? ApiCapabilitySupport.RAG_READ
                : ApiCapabilitySupport.RAG_WRITE;
        if ((ApiCapabilitySupport.RAG_READ.equals(required)
                && ApiCapabilitySupport.hasRead(principal))
                || (ApiCapabilitySupport.RAG_WRITE.equals(required)
                && ApiCapabilitySupport.hasWrite(principal))) {
            filterChain.doFilter(request, response);
            return;
        }

        sendForbidden(request, response, request.getRequestURI(), required);
    }

    private ApiRoutePolicyDecision legacyDecision(HttpServletRequest request) {
        String required = requiredCapability(request.getMethod(), request.getRequestURI());
        if (required == null) {
            return isManagementOrIdentityPath(normalizePath(request.getRequestURI()))
                    ? ApiRoutePolicyDecision.MANAGEMENT
                    : ApiRoutePolicyDecision.UNDECLARED;
        }
        return ApiCapabilitySupport.RAG_READ.equals(required)
                ? ApiRoutePolicyDecision.READ
                : ApiRoutePolicyDecision.WRITE;
    }

    /**
     * Returns the capability required by a routed data-plane request.
     * Package-private for focused classifier tests.
     */
    static String requiredCapability(String method, String requestUri) {
        if (method == null || requestUri == null) {
            return null;
        }
        String path = normalizePath(requestUri);
        if (!path.startsWith("/api/") && !path.startsWith("/v1/")) {
            return null;
        }
        String verb = method.toUpperCase(java.util.Locale.ROOT);
        if (SecurityPathExclusions.isAmbiguous(path)) {
            // 形态可疑的 URI 拿不到任何豁免：既不给"管理/身份路径"的免检，
            // 也不给只读 POST 列表的降级，一律按最严的那一档要求能力。
            // 方向很重要——本过滤器里返回 null 表示"不要求能力"（更宽松），
            // 所以 fail closed 意味着"不能返回 null"。
            return switch (verb) {
                case "GET", "HEAD", "OPTIONS" -> ApiCapabilitySupport.RAG_READ;
                case "POST", "PUT", "PATCH", "DELETE" -> ApiCapabilitySupport.RAG_WRITE;
                default -> ApiCapabilitySupport.RAG_WRITE;
            };
        }
        if (isManagementOrIdentityPath(path)) {
            return null;
        }
        return switch (verb) {
            case "GET", "HEAD", "OPTIONS" -> ApiCapabilitySupport.RAG_READ;
            case "POST" -> READ_POST_PATHS.contains(path)
                    ? ApiCapabilitySupport.RAG_READ
                    : ApiCapabilitySupport.RAG_WRITE;
            case "PUT", "PATCH", "DELETE" -> ApiCapabilitySupport.RAG_WRITE;
            default -> null;
        };
    }

    static boolean isManagementOrIdentityPath(String path) {
        // 分段感知：/api/v1/rag/api-keysfoo 不该被当作身份管理路径。
        return path.equals("/api/v1/rag/auth")
                || SecurityPathExclusions.matchesPrefix(path, "/api/v1/rag/auth")
                || path.equals("/api/v1/rag/api-keys")
                || SecurityPathExclusions.matchesPrefix(path, "/api/v1/rag/api-keys")
                || path.equals("/api/v1/rag/alerts")
                || SecurityPathExclusions.matchesPrefix(path, "/api/v1/rag/alerts")
                || path.equals("/api/v1/rag/integration-capabilities");
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

    private void sendForbidden(HttpServletRequest request,
                               HttpServletResponse response,
                               String path,
                               String requiredCapability) throws IOException {
        String message = "API principal requires capability " + requiredCapability;
        // ApiCapabilityFilter 的 order(-5) 在 FailureClassResponseHeaderFilter(1000)
        // 之前，短路返回时后者还没进入链，所以分类头必须在这里自己写。
        FailureClassHttpSupport.apply(response, HttpStatus.FORBIDDEN.value(), "FORBIDDEN");
        if (path.startsWith("/v1/")) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                    OpenAiErrorResponse.of(
                            message,
                            "permission_error",
                            null,
                            "insufficient_permissions")));
            return;
        }
        NativeErrorResponseWriter.write(
                OBJECT_MAPPER, request, response, HttpStatus.FORBIDDEN.value(),
                "FORBIDDEN", message);
    }

    private void sendRouteNotDeclared(HttpServletRequest request,
                                      HttpServletResponse response, String path)
            throws IOException {
        String message = "API route is not declared in the route policy registry";
        FailureClassHttpSupport.apply(response, HttpStatus.FORBIDDEN.value(), "ROUTE_NOT_DECLARED");
        if (path != null && path.startsWith("/v1/")) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                    OpenAiErrorResponse.of(
                            message,
                            "permission_error",
                            null,
                            "ROUTE_NOT_DECLARED")));
            return;
        }
        NativeErrorResponseWriter.write(
                OBJECT_MAPPER, request, response, HttpStatus.FORBIDDEN.value(),
                "ROUTE_NOT_DECLARED", message);
    }
}
