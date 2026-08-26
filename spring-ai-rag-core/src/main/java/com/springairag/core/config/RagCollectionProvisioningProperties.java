package com.springairag.core.config;

import java.time.Duration;

/**
 * Collection 创建幂等账本配置。
 */
public class RagCollectionProvisioningProperties {

    private boolean enabled = true;
    private Duration retention = Duration.ofDays(400);
    private int cleanupBatchSize = 500;
    private long cleanupIntervalMs = 3_600_000L;
    private int concurrentRetryAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) {
        if (retention == null || retention.compareTo(Duration.ofDays(7)) < 0
                || retention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException(
                    "Collection provisioning retention must be between 7 and 3650 days");
        }
        this.retention = retention;
    }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = Math.max(10, Math.min(5000, cleanupBatchSize));
    }
    public long getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = Math.max(10_000L, Math.min(86_400_000L, cleanupIntervalMs));
    }
    public int getConcurrentRetryAttempts() { return concurrentRetryAttempts; }
    public void setConcurrentRetryAttempts(int concurrentRetryAttempts) {
        this.concurrentRetryAttempts = Math.max(1, Math.min(8, concurrentRetryAttempts));
    }
}
