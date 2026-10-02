package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.openai.OpenAiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.ratelimit.PostgresRateLimitStore;
import com.springairag.core.ratelimit.RateLimitObservability;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 限流过滤器：兼容本地固定窗口，并支持 PostgreSQL 共享固定 UTC 分钟窗口。
 *
 * <p>Local backend supports three rate limiting strategies:
 * <ul>
 *   <li>{@code ip} — Rate limit by client IP address (default)</li>
 *   <li>{@code api-key} — Rate limit by X-API-Key header; falls back to IP if not provided</li>
 *   <li>{@code user} — Rate limit by authenticated user (prefers {@code authenticatedApiKey} request attribute
 *       set by {@link ApiKeyAuthFilter}; falls back to IP if not authenticated)</li>
 * </ul>
 * PostgreSQL backend requires {@code principal} and uses only the authenticated
 * stable database principal established by {@link ApiKeyAuthFilter}.
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
 *   <li>Retry-After response header calculated from the active window</li>
 *   <li>JSON {@link ErrorResponse} body</li>
 * </ul>
 *
 * <p><b>客户端地址来源</b>：默认**不**采信 {@code X-Forwarded-For}。
 * 只有当直连对端属于 {@code rag.rate-limit.trusted-proxies} 时才解析该头，
 * 并且从右往左剥离可信代理、取第一个不可信地址。
 * 早期实现无条件信任该头，于是默认 {@code strategy=ip} 下，
 * 任何客户端换一个伪造的 {@code X-Forwarded-For} 就能拿到全新计数窗口——
 * 这正是本项目 API Key 加固计划列为"必须防御的攻击"的那一条。
 * 详见 {@link TrustedProxyResolver}。
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

    /** 固定窗口长度（毫秒），与 Retry-After 的 60 秒保持一致。 */
    private static final long WINDOW_MILLIS = 60_000L;

    /** 窗口表超过该阈值时做一次过期清扫。 */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final boolean enabled;
    private final int requestsPerMinute;
    private final String strategy;
    private final Map<String, Integer> keyLimits;
    private final String backend;
    private final PostgresRateLimitStore postgresStore;
    private final RateLimitObservability observability;
    private final TrustedProxyResolver trustedProxies;

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
        this(enabled, requestsPerMinute, strategy, keyLimits, "local", null);
    }

    public RateLimitFilter(boolean enabled, int requestsPerMinute,
                           String strategy, Map<String, Integer> keyLimits,
                           String backend, PostgresRateLimitStore postgresStore) {
        this(enabled, requestsPerMinute, strategy, keyLimits, backend,
                postgresStore, RateLimitObservability.noop());
    }

    public RateLimitFilter(boolean enabled, int requestsPerMinute,
                           String strategy, Map<String, Integer> keyLimits,
                           String backend, PostgresRateLimitStore postgresStore,
                           RateLimitObservability observability) {
        this(enabled, requestsPerMinute, strategy, keyLimits, backend, postgresStore,
                observability, TrustedProxyResolver.TRUST_NONE);
    }

    public RateLimitFilter(boolean enabled, int requestsPerMinute,
                           String strategy, Map<String, Integer> keyLimits,
                           String backend, PostgresRateLimitStore postgresStore,
                           RateLimitObservability observability,
                           TrustedProxyResolver trustedProxies) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.strategy = strategy == null ? "ip" : strategy;
        this.keyLimits = keyLimits == null ? Map.of() : Map.copyOf(keyLimits);
        this.backend = backend == null ? "local" : backend;
        this.postgresStore = postgresStore;
        this.observability = observability == null
                ? RateLimitObservability.noop()
                : observability;
        this.trustedProxies = trustedProxies == null
                ? TrustedProxyResolver.TRUST_NONE
                : trustedProxies;
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

        if ("postgresql".equals(backend)) {
            applyPostgresLimit(request, response, filterChain, path);
            return;
        }

        // Resolve client identifier by strategy
        ClientId clientId = resolveClientId(request);
        request.setAttribute(CLIENT_ID_ATTRIBUTE, clientId.identifier);

        int limit = resolveLimit(clientId);
        WindowState state = getOrCreateWindow(clientId.identifier);

        int currentCount = state.incrementAndGet();
        int remaining = limit - currentCount;

        response.setHeader(RATE_LIMIT_LIMIT_HEADER, String.valueOf(limit));
        response.setHeader(RATE_LIMIT_REMAINING_HEADER, String.valueOf(Math.max(0, remaining)));

        if (currentCount > limit) {
            observability.recordDecision(backend, "rejected", clientId.type);
            writeRateLimitResponse(response, path, clientId.type, limit, 60);
            return;
        }

        observability.recordDecision(backend, "allowed", clientId.type);
        log.debug("Local rate-limit decision: result=allowed strategy={}", clientId.type);
        filterChain.doFilter(request, response);
    }

    private void applyPostgresLimit(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String path) throws IOException, ServletException {
        Object principalAttr = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
        if (!(principalAttr instanceof String principalId) || principalId.isBlank()
                || postgresStore == null) {
            observability.recordDecision("postgresql", "error", "UNKNOWN");
            writeStoreUnavailable(response, path);
            return;
        }
        request.setAttribute(CLIENT_ID_ATTRIBUTE, principalId);
        int limit = requestsPerMinute;
        Object snapshot = request.getAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE);
        if (snapshot instanceof AuthenticatedApiPrincipal principal
                && principal.getRequestsPerMinute() != null) {
            limit = principal.getRequestsPerMinute();
        }
        try {
            PostgresRateLimitStore.Decision decision =
                    postgresStore.consume(principalId, limit);
            int remaining = Math.max(0, limit - decision.requestCount());
            response.setHeader(RATE_LIMIT_LIMIT_HEADER, String.valueOf(limit));
            response.setHeader(RATE_LIMIT_REMAINING_HEADER, String.valueOf(remaining));
            if (!decision.allowed()) {
                observability.recordDecision(
                        "postgresql", "rejected", fixedPrincipalType(request));
                writeRateLimitResponse(
                        response, path, "principal", limit,
                        decision.retryAfterSeconds());
                return;
            }
            observability.recordDecision(
                    "postgresql", "allowed", fixedPrincipalType(request));
            log.debug("PostgreSQL rate-limit decision: result=allowed principalType={}",
                    fixedPrincipalType(request));
            filterChain.doFilter(request, response);
        } catch (DataAccessException | IllegalStateException e) {
            observability.recordDecision(
                    "postgresql", "error", fixedPrincipalType(request));
            log.error("PostgreSQL rate-limit decision failed: result=error principalType={}",
                    fixedPrincipalType(request));
            writeStoreUnavailable(response, path);
        }
    }

    private String fixedPrincipalType(HttpServletRequest request) {
        Object type = request.getAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE);
        if (ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY.equals(type)
                || ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT.equals(type)
                || ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC.equals(type)) {
            return String.valueOf(type);
        }
        return "UNKNOWN";
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
            Object authenticatedKey = request.getAttribute(
                    ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE);
            if (authenticatedKey instanceof String key && !key.isBlank()) {
                return new ClientId(key, "api-key");
            }
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
                                        String strategyType, int limit,
                                        int retryAfter) throws IOException {
        log.warn("Rate-limit decision: result=rejected backend={} strategy={}",
                backend, strategyType);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfter));
        if (path != null && path.startsWith("/v1/")) {
            response.getWriter().write(objectMapper.writeValueAsString(
                    OpenAiErrorResponse.of(
                            "Rate limit exceeded. Max " + limit
                                    + " requests per minute.",
                            "rate_limit_error",
                            null,
                            "rate_limit_exceeded")));
            return;
        }
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("TOO_MANY_REQUESTS")
                .message("Rate limit exceeded. Max " + limit + " requests per minute.")
                .path(path)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    private void writeStoreUnavailable(
            HttpServletResponse response,
            String path) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (path != null && path.startsWith("/v1/")) {
            response.getWriter().write(objectMapper.writeValueAsString(
                    OpenAiErrorResponse.of(
                            "Rate limit service is unavailable.",
                            "server_error",
                            null,
                            "rate_limit_store_unavailable")));
            return;
        }
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.builder()
                        .error("RATE_LIMIT_STORE_UNAVAILABLE")
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .message("Rate limit service is unavailable.")
                        .path(path)
                        .build()));
    }

    /**
     * Gets or creates a window state; expired windows are automatically reset.
     */
    private WindowState getOrCreateWindow(String identifier) {
        sweepExpiredWindows();
        return windows.compute(identifier, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart >= WINDOW_MILLIS) {
                return new WindowState(now);
            }
            return existing;
        });
    }

    /**
     * 丢弃已经过了至少一个完整窗口的计数条目。
     *
     * <p>{@code windows} 曾经只增不减：每个新客户端标识留下一条记录，
     * 直到进程重启才会消失。即便修好了 {@code X-Forwarded-For} 信任问题，
     * 客户端地址空间本身也足够大（IPv6 尤其如此），一条永不回收的映射
     * 就是一条可被慢慢撑爆的内存增长路径。这里在表超过阈值时做一次机会式清扫：
     * 每次请求 O(n) 不现实，但超阈值才扫、且只删已过期的，成本可以忽略。
     */
    private void sweepExpiredWindows() {
        if (windows.size() < SWEEP_THRESHOLD) {
            return;
        }
        long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
        windows.entrySet().removeIf(entry -> entry.getValue().windowStart < cutoff);
    }

    boolean isExcludedPath(String path) {
        return path.startsWith("/actuator") ||
                path.startsWith("/swagger-ui") ||
                path.startsWith("/v3/api-docs") ||
                path.startsWith("/health") ||
                path.startsWith("/error");
    }

    /**
     * Resolves client IP address.
     *
     * <p>{@code X-Forwarded-For} 只有在**直连对端本身是可信代理**时才被采信，
     * 并且从右往左剥离可信代理、取第一个不可信地址。对端不可信时该头被完全忽略——
     * 否则任何客户端都能靠换一个伪造的 {@code X-Forwarded-For} 拿到全新窗口，
     * 默认 {@code strategy=ip} 下限流等于不存在。
     */
    String resolveClientIp(HttpServletRequest request) {
        return trustedProxies.resolveClientAddress(
                request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
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
