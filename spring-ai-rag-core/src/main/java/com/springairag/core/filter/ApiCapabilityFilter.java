package com.springairag.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
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

        String required = requiredCapability(request.getMethod(), request.getRequestURI());
        if (required == null
                || (ApiCapabilitySupport.RAG_READ.equals(required)
                && ApiCapabilitySupport.hasRead(principal))
                || (ApiCapabilitySupport.RAG_WRITE.equals(required)
                && ApiCapabilitySupport.hasWrite(principal))) {
            filterChain.doFilter(request, response);
            return;
        }

        sendForbidden(response, request.getRequestURI(), required);
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
        if (isManagementOrIdentityPath(path)) {
            return null;
        }
        return switch (method.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> ApiCapabilitySupport.RAG_READ;
            case "POST" -> READ_POST_PATHS.contains(path)
                    ? ApiCapabilitySupport.RAG_READ
                    : ApiCapabilitySupport.RAG_WRITE;
            case "PUT", "PATCH", "DELETE" -> ApiCapabilitySupport.RAG_WRITE;
            default -> null;
        };
    }

    private static boolean isManagementOrIdentityPath(String path) {
        return path.equals("/api/v1/rag/auth")
                || path.startsWith("/api/v1/rag/auth/")
                || path.equals("/api/v1/rag/api-keys")
                || path.startsWith("/api/v1/rag/api-keys/")
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

    private void sendForbidden(HttpServletResponse response,
                               String path,
                               String requiredCapability) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = "API principal requires capability " + requiredCapability;
        if (path.startsWith("/v1/")) {
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                    OpenAiErrorResponse.of(
                            message,
                            "permission_error",
                            null,
                            "insufficient_permissions")));
            return;
        }
        ErrorResponse error = ErrorResponse.builder()
                .error("FORBIDDEN")
                .status(HttpStatus.FORBIDDEN.value())
                .message(message)
                .path(path)
                .build();
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(error));
    }
}
