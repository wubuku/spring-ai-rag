package com.springairag.core.config;

import java.util.List;

/**
 * CORS 跨域配置
 *
 * <p>配置示例：
 * <pre>
 * rag:
 *   cors:
 *     enabled: true
 *     allowed-origins:
 *       - "https://example.com"
 *       - "http://localhost:3000"
 *     allowed-methods: "GET,POST,PUT,DELETE,OPTIONS"
 *     allowed-headers: "*"
 *     max-age: 3600
 * </pre>
 */
public class RagCorsProperties {

    private boolean enabled = false;
    private List<String> allowedOrigins = List.of("*");
    private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
    private String allowedHeaders = "*";
    private long maxAge = 3600;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(String allowedMethods) {
        this.allowedMethods = allowedMethods;
    }

    public String getAllowedHeaders() {
        return allowedHeaders;
    }

    public void setAllowedHeaders(String allowedHeaders) {
        this.allowedHeaders = allowedHeaders;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }
}
