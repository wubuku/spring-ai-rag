package com.springairag.core.config;

import java.time.Duration;

/** 有界 API credential 轮换配置。 */
public class RagApiKeyRotationProperties {

    private Duration defaultOverlap = Duration.ofMinutes(15);
    private Duration maxOverlap = Duration.ofHours(1);
    private Duration operationRetention = Duration.ofDays(400);
    private long cleanupIntervalMs = 60_000;
    private int cleanupBatchSize = 500;

    public Duration getDefaultOverlap() { return defaultOverlap; }
    public void setDefaultOverlap(Duration defaultOverlap) {
        validateWholeSeconds(defaultOverlap, "Default overlap");
        if (defaultOverlap.isZero() || defaultOverlap.isNegative()
                || defaultOverlap.compareTo(maxOverlap) > 0) {
            throw new IllegalArgumentException(
                    "Default overlap must be positive and no greater than max overlap");
        }
        this.defaultOverlap = defaultOverlap;
    }

    public Duration getMaxOverlap() { return maxOverlap; }
    public void setMaxOverlap(Duration maxOverlap) {
        validateWholeSeconds(maxOverlap, "Max overlap");
        if (maxOverlap.compareTo(Duration.ofSeconds(1)) < 0
                || maxOverlap.compareTo(Duration.ofHours(24)) > 0
                || defaultOverlap.compareTo(maxOverlap) > 0) {
            throw new IllegalArgumentException(
                    "Max overlap must be between 1 second and 24 hours and cover the default");
        }
        this.maxOverlap = maxOverlap;
    }

    public Duration getOperationRetention() { return operationRetention; }
    public void setOperationRetention(Duration operationRetention) {
        if (operationRetention == null
                || operationRetention.getNano() != 0
                || operationRetention.getSeconds() % Duration.ofDays(1).getSeconds() != 0
                || operationRetention.compareTo(Duration.ofDays(7)) < 0
                || operationRetention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException(
                    "Rotation operation retention must be whole days between 7 and 3650");
        }
        this.operationRetention = operationRetention;
    }

    public long getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        if (cleanupIntervalMs < 1_000 || cleanupIntervalMs > Duration.ofDays(1).toMillis()) {
            throw new IllegalArgumentException(
                    "Rotation cleanup interval must be between 1000 and 86400000 ms");
        }
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) {
        if (cleanupBatchSize < 10 || cleanupBatchSize > 5000) {
            throw new IllegalArgumentException(
                    "Rotation cleanup batch size must be between 10 and 5000");
        }
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public int defaultOverlapSeconds() {
        return Math.toIntExact(defaultOverlap.getSeconds());
    }

    public int maxOverlapSeconds() {
        return Math.toIntExact(maxOverlap.getSeconds());
    }

    public int operationRetentionDays() {
        return Math.toIntExact(operationRetention.toDays());
    }

    private void validateWholeSeconds(Duration value, String name) {
        if (value == null || value.getNano() != 0) {
            throw new IllegalArgumentException(name + " must use whole seconds");
        }
    }
}
