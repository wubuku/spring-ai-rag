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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 限流过滤器
 *
 * <p>基于滑动窗口计数器，支持两种限流策略：
 * <ul>
 *   <li>{@code ip} — 按客户端 IP 限流（默认）</li>
 *   <li>{@code api-key} — 按 API Key 限流，未携带 Key 时回退到 IP</li>
 * </ul>
 *
 * <p>当 {@code strategy=api-key} 且配置了 {@code keyLimits} 时，
 * 为每个 API Key 使用独立限额；未配置的 Key 使用默认限额。
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

    /** 请求属性：限流标识符（供测试和日志使用） */
    public static final String CLIENT_ID_ATTRIBUTE = "rateLimitClientId";

    private static final String API_KEY_HEADER = "X-API-Key";

    private final boolean enabled;
    private final int requestsPerMinute;
    private final String strategy;
    private final Map<String, Integer> keyLimits;

    /** 标识符 → 窗口状态 */
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();

    /**
     * 便捷构造函数（向后兼容，等价 strategy=ip）
     */
    public RateLimitFilter(boolean enabled, int requestsPerMinute) {
        this(enabled, requestsPerMinute, "ip", Map.of());
    }

    /**
     * 完整构造函数
     *
     * @param enabled           是否启用
     * @param requestsPerMinute 默认每分钟限额
     * @param strategy          限流策略（ip 或 api-key）
     * @param keyLimits         API Key → 自定义限额映射（strategy=api-key 时生效）
     */
    public RateLimitFilter(boolean enabled, int requestsPerMinute,
                           String strategy, Map<String, Integer> keyLimits) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.strategy = strategy == null ? "ip" : strategy;
        this.keyLimits = keyLimits == null ? Map.of() : Map.copyOf(keyLimits);
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

        // 按策略解析标识符
        ClientId clientId = resolveClientId(request);
        request.setAttribute(CLIENT_ID_ATTRIBUTE, clientId.identifier);

        int limit = resolveLimit(clientId);
        WindowState state = getOrCreateWindow(clientId.identifier);

        int currentCount = state.incrementAndGet();
        int remaining = limit - currentCount;

        // 设置限流响应头
        response.setHeader(RATE_LIMIT_LIMIT_HEADER, String.valueOf(limit));
        response.setHeader(RATE_LIMIT_REMAINING_HEADER, String.valueOf(Math.max(0, remaining)));

        if (currentCount > limit) {
            writeRateLimitResponse(response, path, clientId, currentCount, limit);
            return;
        }

        log.debug("Rate limit count: {} {} id={} {}/{}", request.getMethod(), path,
                clientId.identifier, currentCount, limit);
        filterChain.doFilter(request, response);
    }

    /**
     * 按策略解析客户端标识符
     */
    private ClientId resolveClientId(HttpServletRequest request) {
        if ("api-key".equals(strategy)) {
            String apiKey = request.getHeader(API_KEY_HEADER);
            if (apiKey != null && !apiKey.isBlank()) {
                return new ClientId(apiKey, "api-key");
            }
        }
        // ip 策略或 api-key 未携带时回退到 IP
        return new ClientId(resolveClientIp(request), "ip");
    }

    /**
     * 解析该标识符对应的限额
     */
    private int resolveLimit(ClientId clientId) {
        if ("api-key".equals(clientId.type) && !keyLimits.isEmpty()) {
            Integer customLimit = keyLimits.get(clientId.identifier);
            if (customLimit != null && customLimit > 0) {
                return customLimit;
            }
        }
        return requestsPerMinute;
    }

    private void writeRateLimitResponse(HttpServletResponse response, String path,
                                        ClientId clientId, int currentCount,
                                        int limit) throws IOException {
        log.warn("Rate limit triggered: {}={} path={} count={}/{}",
                clientId.type, clientId.identifier, path, currentCount, limit);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RETRY_AFTER_HEADER, "60");
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("TOO_MANY_REQUESTS")
                .message("Rate limit exceeded. Max " + limit + " requests per minute.")
                .path(path)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    /**
     * 获取或创建窗口状态，过期窗口自动重置
     */
    private WindowState getOrCreateWindow(String identifier) {
        return windows.compute(identifier, (key, existing) -> {
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
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    // ==================== 内部类 ====================

    /**
     * 客户端标识符（含类型信息）
     */
    static class ClientId {
        final String identifier;
        final String type; // "ip" or "api-key"

        ClientId(String identifier, String type) {
            this.identifier = identifier;
            this.type = type;
        }
    }

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
