package com.springairag.core.config;

import com.springairag.core.filter.TrustedProxyResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API 限流配置：local 兼容模式或 PostgreSQL 共享 principal 配额。
 *
 * <p>Local backend supports three strategies:
 * <ul>
 *   <li>{@code ip} — Rate limit by client IP (default, backward compatible)</li>
 *   <li>{@code api-key} — Rate limit by X-API-Key request header, falls back to IP when not provided</li>
 *   <li>{@code user} — Rate limit by authenticated user; reads {@code authenticatedApiKey} request attribute
 *       (set by {@link com.springairag.core.filter.ApiKeyAuthFilter} after successful authentication),
 *       falls back to IP when not authenticated</li>
 * </ul>
 * PostgreSQL backend requires {@code principal}; it never falls back to a raw
 * request credential or IP address.
 *
 * <p>Example:
 * <pre>
 * rag:
 *   rate-limit:
 *     enabled: true
 *     backend: postgresql          # local | postgresql
 *     requests-per-minute: 60
 *     strategy: principal          # postgresql requires principal
 * </pre>
 */
public class RagRateLimitProperties {

    private boolean enabled = false;
    private int requestsPerMinute = 60;
    private String strategy = "ip";
    private String backend = "local";
    private Map<String, Integer> keyLimits = new HashMap<>();
    private int bucketRetentionMinutes = 1440;
    private int cleanupIntervalSeconds = 300;
    private int cleanupBatchSize = 10_000;

    /**
     * 可信代理地址或网段（CIDR），例如 {@code 10.0.0.0/8}、{@code 192.168.1.5}、{@code ::1}。
     *
     * <p><b>默认为空，即不采信 {@code X-Forwarded-For}</b>。只有当直连对端落在
     * 这个集合里时，过滤器才解析该头并从右往左剥离可信代理。
     * 早期实现无条件信任该头，默认 {@code strategy=ip} 下限流可被伪造头完全绕过。
     *
     * <p>部署在反向代理后面时必须显式配置，否则所有请求会算在代理 IP 上、
     * 共享同一个计数窗口。
     */
    private List<String> trustedProxies = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }

    public Map<String, Integer> getKeyLimits() {
        return keyLimits;
    }

    public void setKeyLimits(Map<String, Integer> keyLimits) {
        this.keyLimits = keyLimits;
    }

    public int getBucketRetentionMinutes() { return bucketRetentionMinutes; }
    public void setBucketRetentionMinutes(int bucketRetentionMinutes) { this.bucketRetentionMinutes = bucketRetentionMinutes; }
    public int getCleanupIntervalSeconds() { return cleanupIntervalSeconds; }
    public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) { this.cleanupIntervalSeconds = cleanupIntervalSeconds; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }

    public List<String> getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : new ArrayList<>(trustedProxies);
    }

    public void validateTopology() {
        if (!"local".equals(backend) && !"postgresql".equals(backend)) {
            throw new IllegalStateException("rag.rate-limit.backend must be local or postgresql");
        }
        if ("postgresql".equals(backend)) {
            if (!"principal".equals(strategy)) {
                throw new IllegalStateException(
                        "PostgreSQL rate limiting requires strategy=principal");
            }
            if (keyLimits != null && !keyLimits.isEmpty()) {
                throw new IllegalStateException(
                        "PostgreSQL rate limiting does not support key-limits");
            }
        }
        if (bucketRetentionMinutes <= 0 || cleanupIntervalSeconds <= 0
                || cleanupBatchSize <= 0) {
            throw new IllegalStateException("Rate limit cleanup values must be positive");
        }
        // 代理条目必须能在启动期解析：写错一个网段应该让进程起不来，
        // 而不是等到第一个被限流的请求才发现客户端地址算错了。
        TrustedProxyResolver.of(trustedProxies);
    }
}
