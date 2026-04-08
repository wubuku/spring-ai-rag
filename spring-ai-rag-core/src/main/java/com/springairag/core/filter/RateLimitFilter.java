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

import com.springairag.core.filter.ApiKeyAuthFilter;

/**
 * API rate limiting filter based on sliding window counter.
 *
 * <p>Supports three rate limiting strategies:
 * <ul>
 *   <li>{@code ip} — Rate limit by client IP address (default)</li>
 *   <li>{@code api-key} — Rate limit by X-API-Key header; falls back to IP if not provided</li>
 *   <li>{@code user} — Rate limit by authenticated user (prefers {@code authenticatedApiKey} request attribute
 *       set by {@link ApiKeyAuthFilter}; falls back to IP if not authenticated)</li>
 * </ul>
 *
 * <p>When {@code strategy=api-key} and {@code keyLimits} is configured,
 * each API key gets its own limit; unconfigured keys use the default limit.
 *
 * <p>Requests pass through directly when rate limiting is disabled or requestsPerMinute ≤ 0.
 *
 * <p>Excluded paths (not rate limited):
 * <ul>
 *   <li>/actuator/** — Health checks</li>
 *   <li>/swagger-ui/** — API documentation</li>
 *   <li>/v3/api-docs — OpenAPI specification</li>
 *   <li>/health — Health check</li>
 *   <li>/error — Spring error page</li>
 * </ul>
 *
 * <p>Rate limit response:
 * <ul>
 *   <li>HTTP 429 Too Many Requests</li>
 *   <li>Retry-After: 60 response header</li>
 *   <li>JSON {@link ErrorResponse} body</li>
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Response header: seconds remaining in the rate limit window */
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    /** Response header: number of requests used in current window */
    public static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";

    /** Response header: maximum requests allowed in window */
    public static final String RATE_LIMIT_LIMIT_HEADER = "X-RateLimit-Limit";

    /** Request attribute: rate limit client identifier (for testing and logging) */
    public static final String CLIENT_ID_ATTRIBUTE = "rateLimitClientId";

    private static final String API_KEY_HEADER = "X-API-Key";

    private final boolean enabled;
    private final int requestsPerMinute;
    private final String strategy;
    private final Map<String, Integer> keyLimits;

    /** Identifier to window state mapping */
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();

    /**
     * Convenience constructor (backward compatible, equivalent to strategy=ip).
     */
    public RateLimitFilter(boolean enabled, int requestsPerMinute) {
        this(enabled, requestsPerMinute, "ip", Map.of());
    }

    /**
     * Full constructor.
     *
     * @param enabled           Whether rate limiting is enabled
     * @param requestsPerMinute Default requests per minute limit
     * @param strategy          Rate limiting strategy (ip or api-key)
     * @param keyLimits         API key to custom limit mapping (effective when strategy=api-key or strategy=user)
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

        // Resolve client identifier by strategy
        ClientId clientId = resolveClientId(request);
        request.setAttribute(CLIENT_ID_ATTRIBUTE, clientId.identifier);

        int limit = resolveLimit(clientId);
        WindowState state = getOrCreateWindow(clientId.identifier);

        int currentCount = state.incrementAndGet();
        int remaining = limit - currentCount;

        // Set rate limit response headers
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
     * Resolves client identifier based on the configured strategy.
     */
    private ClientId resolveClientId(HttpServletRequest request) {
        if ("user".equals(strategy)) {
            // Prefer authenticated user identity (set by ApiKeyAuthFilter)
            Object authenticatedKey = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
            if (authenticatedKey instanceof String key && !((String) key).isBlank()) {
                return new ClientId(key, "user");
            }
            // Fall back to IP when not authenticated
            return new ClientId(resolveClientIp(request), "ip");
        }
        if ("api-key".equals(strategy)) {
            String apiKey = request.getHeader(API_KEY_HEADER);
            if (apiKey != null && !apiKey.isBlank()) {
                return new ClientId(apiKey, "api-key");
            }
        }
        // Fall back to IP for ip strategy or when api-key is not provided
        return new ClientId(resolveClientIp(request), "ip");
    }

    /**
     * Resolves the rate limit for the given client identifier.
     */
    private int resolveLimit(ClientId clientId) {
        if (("api-key".equals(clientId.type) || "user".equals(clientId.type))
                && !keyLimits.isEmpty()) {
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
     * Gets or creates a window state; expired windows are automatically reset.
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
     * Resolves client IP address, preferring X-Forwarded-For header, falling back to RemoteAddr.
     */
    String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    // ==================== Inner Classes ====================

    /**
     * Client identifier with type information.
     */
    static class ClientId {
        final String identifier;
        final String type; // "ip", "api-key", or "user"

        ClientId(String identifier, String type) {
            this.identifier = identifier;
            this.type = type;
        }
    }

    /**
     * Sliding window state.
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

    // ==================== Test Helpers ====================

    /** Returns the current window states (for testing). */
    ConcurrentHashMap<String, WindowState> getWindows() {
        return windows;
    }

    /** Clears all windows (for testing). */
    void clearWindows() {
        windows.clear();
    }
}
