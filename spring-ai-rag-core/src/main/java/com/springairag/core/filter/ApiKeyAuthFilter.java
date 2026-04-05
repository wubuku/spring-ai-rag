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
 * API Key 认证过滤器
 *
 * <p>检查请求头 X-API-Key 是否与配置的 API Key 匹配。
 * 认证关闭或 API Key 为空时直接放行。
 *
 * <p>排除路径（不需要认证）：
 * <ul>
 *   <li>/actuator/** — 健康检查</li>
 *   <li>/swagger-ui/** — API 文档</li>
 *   <li>/v3/api-docs — OpenAPI 规范</li>
 *   <li>/health — 健康检查</li>
 *   <li>/error — Spring 错误页</li>
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
            log.warn("API Key 缺失: {} {}", request.getMethod(), path);
            sendUnauthorized(response, "Missing API Key. Provide X-API-Key header.");
            return;
        }

        if (!configuredApiKey.equals(requestApiKey)) {
            log.warn("API Key 无效: {} {}", request.getMethod(), path);
            sendUnauthorized(response, "Invalid API Key.");
            return;
        }

        log.debug("API Key 验证通过: {} {}", request.getMethod(), path);
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
