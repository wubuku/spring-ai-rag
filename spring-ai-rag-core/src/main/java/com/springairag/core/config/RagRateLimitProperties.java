package com.springairag.core.config;

import java.util.HashMap;
import java.util.Map;

/**
 * API Rate Limiting Configuration
 *
 * <p>Supports three strategies:
 * <ul>
 *   <li>{@code ip} — Rate limit by client IP (default, backward compatible)</li>
 *   <li>{@code api-key} — Rate limit by X-API-Key request header, falls back to IP when not provided</li>
 *   <li>{@code user} — Rate limit by authenticated user; reads {@code authenticatedApiKey} request attribute
 *       (set by {@link com.springairag.core.filter.ApiKeyAuthFilter} after successful authentication),
 *       falls back to IP when not authenticated</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * rag:
 *   rate-limit:
 *     enabled: true
 *     requests-per-minute: 60
 *     strategy: user               # ip | api-key | user
 *     key-limits:                  # effective when strategy=user or api-key
 *       sk-premium-key: 300        # 300 requests/minute for premium users
 *       sk-basic-key: 100          # 100 requests/minute for basic users
 * </pre>
 */
public class RagRateLimitProperties {

    private boolean enabled = false;
    private int requestsPerMinute = 60;
    private String strategy = "ip";
    private Map<String, Integer> keyLimits = new HashMap<>();

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

    public Map<String, Integer> getKeyLimits() {
        return keyLimits;
    }

    public void setKeyLimits(Map<String, Integer> keyLimits) {
        this.keyLimits = keyLimits;
    }
}
