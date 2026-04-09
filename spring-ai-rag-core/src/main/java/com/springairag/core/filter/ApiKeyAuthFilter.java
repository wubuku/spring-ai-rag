package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API Key authentication filter
 *
 * <p>Checks whether the X-API-Key request header matches the configured API key.
 * Passes through when authentication is disabled or API key is empty.
 *
 * <p>Excluded paths (no authentication required):
 * <ul>
 *   <li>/actuator/** — health checks</li>
 *   <li>/swagger-ui/** — API documentation</li>
 *   <li>/v3/api-docs — OpenAPI specification</li>
 *   <li>/health — health check</li>
 *   <li>/error — Spring error page</li>
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Request attribute: API key identity after successful auth (used by downstream like RateLimitFilter) */
    public static final String AUTHENTICATED_KEY_ATTRIBUTE = "authenticatedApiKey";

    private final String configuredApiKey;
    private final boolean authEnabled;

    public ApiKeyAuthFilter(String configuredApiKey, boolean authEnabled) {
        this.configuredApiKey = configuredApiKey;
        this.authEnabled = authEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!authEnabled || configuredApiKey == null || configuredApiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isExcludedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestApiKey = request.getHeader(API_KEY_HEADER);

        if (requestApiKey == null || requestApiKey.isBlank()) {
            log.warn("API Key missing: {} {}", request.getMethod(), path);
            sendUnauthorized(response, "Missing API Key. Provide X-API-Key header.");
            return;
        }

        if (!configuredApiKey.equals(requestApiKey)) {
            log.warn("API Key invalid: {} {}", request.getMethod(), path);
            sendUnauthorized(response, "Invalid API Key.");
            return;
        }

        log.debug("API Key validated: {} {}", request.getMethod(), path);
        // Set authenticated identity for downstream RateLimitFilter and other components
        request.setAttribute(AUTHENTICATED_KEY_ATTRIBUTE, requestApiKey);
        filterChain.doFilter(request, response);
    }

    private boolean isExcludedPath(String path) {
        return path.startsWith("/actuator") ||
                path.startsWith("/swagger-ui") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/health") ||
                path.startsWith("/api/v1/rag/cache") || // Cache metrics/invalidate - admin read-only
                path.startsWith("/error");
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .message(message)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
