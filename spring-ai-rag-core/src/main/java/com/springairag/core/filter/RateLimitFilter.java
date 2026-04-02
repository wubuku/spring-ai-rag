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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 限流过滤器
 *
 * <p>基于滑动窗口计数器（简化令牌桶），按客户端 IP 限流。
 * 每个 IP 在每分钟窗口内最多允许指定次数的请求。
 *
 * <p>限流关闭或 requestsPerMinute ≤ 0 时直接放行。
 *
 * <p>排除路径（不需要限流）：
 * <ul>
 *   <li>/actuator/** — 健康检查</li>
 *   <li>/swagger-ui/** — API 文档</li>
 *   <li>/v3/api-docs — OpenAPI 规范</li>
 *   <li>/health — 健康检查</li>
 *   <li>/error — Spring 错误页</li>
 * </ul>
 *
 * <p>限流响应：
 * <ul>
 *   <li>HTTP 429 Too Many Requests</li>
 *   <li>响应头 Retry-After: 60</li>
 *   <li>响应体 JSON {@link ErrorResponse}</li>
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 响应头：限流窗口剩余秒数 */
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    /** 响应头：当前窗口已用请求数 */
    public static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";

    /** 响应头：窗口内最大请求数 */
    public static final String RATE_LIMIT_LIMIT_HEADER = "X-RateLimit-Limit";

    private final boolean enabled;
    private final int requestsPerMinute;

    /** IP → 窗口状态 */
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(boolean enabled, int requestsPerMinute) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || requestsPerMinute <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isExcludedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        WindowState state = getOrCreateWindow(clientIp);

        int currentCount = state.incrementAndGet();
        int remaining = requestsPerMinute - currentCount;

        // 设置限流响应头
        response.setHeader(RATE_LIMIT_LIMIT_HEADER, String.valueOf(requestsPerMinute));
        response.setHeader(RATE_LIMIT_REMAINING_HEADER, String.valueOf(Math.max(0, remaining)));

        if (currentCount > requestsPerMinute) {
            log.warn("限流触发: {} {} ip={} count={}/{}", request.getMethod(), path, clientIp, currentCount, requestsPerMinute);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(RETRY_AFTER_HEADER, "60");
            ErrorResponse errorResponse = ErrorResponse.builder()
                    .error("TOO_MANY_REQUESTS")
                    .message("Rate limit exceeded. Max " + requestsPerMinute + " requests per minute.")
                    .path(path)
                    .build();
            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            return;
        }

        log.debug("限流计数: {} {} ip={} {}/{}", request.getMethod(), path, clientIp, currentCount, requestsPerMinute);
        filterChain.doFilter(request, response);
    }

    /**
     * 获取或创建窗口状态，过期窗口自动重置
     */
    private WindowState getOrCreateWindow(String clientIp) {
        return windows.compute(clientIp, (ip, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart >= 60_000) {
                return new WindowState(now);
            }
            return existing;
        });
    }

    boolean isExcludedPath(String path) {
        return path.startsWith("/actuator") ||
                path.startsWith("/swagger-ui") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/health") ||
                path.startsWith("/error");
    }

    /**
     * 解析客户端 IP，优先 X-Forwarded-For，兜底 RemoteAddr
     */
    String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 取第一个 IP（最靠近客户端的）
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    // ==================== 内部类 ====================

    /**
     * 滑动窗口状态
     */
    static class WindowState {
        final long windowStart;
        final AtomicInteger count;

        WindowState(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(0);
        }

        int incrementAndGet() {
            return count.incrementAndGet();
        }
    }

    // ==================== 测试辅助 ====================

    /** 获取当前窗口状态（测试用） */
    ConcurrentHashMap<String, WindowState> getWindows() {
        return windows;
    }

    /** 清空所有窗口（测试用） */
    void clearWindows() {
        windows.clear();
    }
}
