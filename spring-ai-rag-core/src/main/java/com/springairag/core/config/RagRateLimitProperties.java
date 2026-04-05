package com.springairag.core.config;

import java.util.HashMap;
import java.util.Map;

/**
 * API 限流配置
 *
 * <p>支持三种策略：
 * <ul>
 *   <li>{@code ip} — 按客户端 IP 限流（默认，向后兼容）</li>
 *   <li>{@code api-key} — 按 X-API-Key 请求头限流，未携带时回退到 IP</li>
 *   <li>{@code user} — 按已认证用户限流，优先从 {@code authenticatedApiKey} 请求属性获取
 *       （该属性由 {@link com.springairag.core.filter.ApiKeyAuthFilter} 在认证成功后设置），
 *       未认证时回退到 IP</li>
 * </ul>
 *
 * <p>配置示例：
 * <pre>
 * rag:
 *   rate-limit:
 *     enabled: true
 *     requests-per-minute: 60
 *     strategy: user               # ip | api-key | user
 *     key-limits:                  # strategy=user 或 api-key 时生效
 *       sk-premium-key: 300        # 高级用户 300 次/分钟
 *       sk-basic-key: 100          # 基础用户 100 次/分钟
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
