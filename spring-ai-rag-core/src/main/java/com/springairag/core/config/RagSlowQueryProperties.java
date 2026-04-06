package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

/**
 * Slow query monitoring configuration.
 *
 * <p>Controls slow SQL query detection, logging, and metrics exposure.
 */
public class RagSlowQueryProperties {

    /** Enable slow query monitoring. */
    private boolean enabled = true;

    /**
     * Slow query threshold in milliseconds.
     * Queries exceeding this threshold are logged and counted as slow.
     */
    private long thresholdMs = 1000;

    /**
     * Whether to log slow query details to the application log.
     */
    private boolean logEnabled = true;

    /**
     * Maximum number of slow queries to retain in memory for exposure via REST.
     * 0 = unlimited (retain all)
     */
    private int maxRetained = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getThresholdMs() {
        return thresholdMs;
    }

    public void setThresholdMs(long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    public boolean isLogEnabled() {
        return logEnabled;
    }

    public void setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
    }

    public int getMaxRetained() {
        return maxRetained;
    }

    public void setMaxRetained(int maxRetained) {
        this.maxRetained = maxRetained;
    }
}
