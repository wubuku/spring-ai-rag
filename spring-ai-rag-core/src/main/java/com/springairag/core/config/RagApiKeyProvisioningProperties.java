package com.springairag.core.config;

import java.time.Duration;

/**
 * 幂等 API Key provisioning ledger configuration.
 */
public class RagApiKeyProvisioningProperties {

    private boolean enabled = true;
    private Duration retention = Duration.ofDays(400);
    private int cleanupBatchSize = 500;
    private int concurrentRetryAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) {
        if (retention == null || retention.compareTo(Duration.ofDays(7)) < 0
                || retention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("Provisioning retention must be between 7 and 3650 days");
        }
        this.retention = retention;
    }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = Math.max(10, Math.min(5000, cleanupBatchSize));
    }
    public int getConcurrentRetryAttempts() { return concurrentRetryAttempts; }
    public void setConcurrentRetryAttempts(int concurrentRetryAttempts) {
        this.concurrentRetryAttempts = Math.max(1, Math.min(8, concurrentRetryAttempts));
    }
}
