package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for API SLO (Service Level Objective) tracking.
 *
 * <p>SLO tracking monitors whether API endpoints meet their latency targets.
 * Each endpoint can have a custom threshold (in milliseconds).
 *
 * <p>Example configuration in application.yml:
 * <pre>
 * rag:
 *   slo:
 *     enabled: true
 *     window-seconds: 300
 *     thresholds:
 *       rag.search.post: 500
 *       rag.search.get: 500
 *       rag.chat.ask: 1000
 *       rag.chat.stream: 1500
 *       rag.documents.embed: 2000
 * </pre>
 */
@ConfigurationProperties(prefix = "rag.slo")
public class ApiSloProperties {

    /**
     * Whether SLO tracking is enabled.
     */
    private boolean enabled = true;

    /**
     * Time window (in seconds) for compliance calculation.
     * Only requests within this window are considered for compliance percentage.
     * Default: 5 minutes (300 seconds).
     */
    private int windowSeconds = 300;

    /**
     * Per-endpoint SLO thresholds in milliseconds.
     * Key: Micrometer timer name (matches @Timed value on controller method).
     * Value: SLO threshold in ms.
     *
     * <p>Default thresholds:
     * <ul>
     *   <li>rag.search.post / rag.search.get: 500ms</li>
     *   <li>rag.chat.ask: 1000ms</li>
     *   <li>rag.chat.stream: 1500ms</li>
     *   <li>rag.documents.embed: 2000ms</li>
     * </ul>
     */
    private Map<String, Long> thresholds = new HashMap<>();

    public ApiSloProperties() {
        // Default thresholds
        thresholds.put("rag.search.post", 500L);
        thresholds.put("rag.search.get", 500L);
        thresholds.put("rag.chat.ask", 1000L);
        thresholds.put("rag.chat.stream", 1500L);
        thresholds.put("rag.documents.embed", 2000L);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public Map<String, Long> getThresholds() {
        return thresholds;
    }

    public void setThresholds(Map<String, Long> thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * Get the SLO threshold for a specific endpoint.
     *
     * @param endpoint the endpoint identifier (timer name)
     * @return the threshold in milliseconds, or 500ms as default
     */
    public long getThreshold(String endpoint) {
        return thresholds.getOrDefault(endpoint, 500L);
    }
}
