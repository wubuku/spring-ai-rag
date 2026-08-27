package com.springairag.core.config;

import java.time.Duration;

/**
 * 外部业务接入数据面可观测性配置。
 */
public class RagIntegrationObservabilityProperties {

    private boolean enabled = true;
    private Duration retention = Duration.ofDays(90);
    private Duration maxQueryRange = Duration.ofDays(31);
    private int maxCollectionBreakdownItems = 100;
    private int queueCapacity = 10_000;
    private int flushBatchSize = 500;
    private Duration flushInterval = Duration.ofSeconds(1);
    private Duration shutdownDrainTimeout = Duration.ofSeconds(5);
    private int cleanupBatchSize = 5_000;
    private Duration cleanupInterval = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public Duration getMaxQueryRange() {
        return maxQueryRange;
    }

    public void setMaxQueryRange(Duration maxQueryRange) {
        this.maxQueryRange = maxQueryRange;
    }

    public int getMaxCollectionBreakdownItems() {
        return maxCollectionBreakdownItems;
    }

    public void setMaxCollectionBreakdownItems(int maxCollectionBreakdownItems) {
        this.maxCollectionBreakdownItems = maxCollectionBreakdownItems;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public Duration getShutdownDrainTimeout() {
        return shutdownDrainTimeout;
    }

    public void setShutdownDrainTimeout(Duration shutdownDrainTimeout) {
        this.shutdownDrainTimeout = shutdownDrainTimeout;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public int retentionDays() {
        return Math.toIntExact(retention.toDays());
    }

    public int maxQueryRangeDays() {
        return Math.toIntExact(maxQueryRange.toDays());
    }

    public void validate() {
        requireDurationRange(
                "rag.integration-observability.retention",
                retention,
                Duration.ofDays(7),
                Duration.ofDays(730));
        requireWholeDays(
                "rag.integration-observability.retention",
                retention);
        requireDurationRange(
                "rag.integration-observability.max-query-range",
                maxQueryRange,
                Duration.ofDays(1),
                Duration.ofDays(90));
        requireWholeDays(
                "rag.integration-observability.max-query-range",
                maxQueryRange);
        if (maxQueryRange.compareTo(retention) > 0) {
            throw new IllegalArgumentException(
                    "rag.integration-observability.max-query-range "
                            + "must not exceed retention");
        }
        requireRange(
                "rag.integration-observability.max-collection-breakdown-items",
                maxCollectionBreakdownItems,
                1,
                1_000);
        requireRange(
                "rag.integration-observability.queue-capacity",
                queueCapacity,
                100,
                100_000);
        requireRange(
                "rag.integration-observability.flush-batch-size",
                flushBatchSize,
                10,
                5_000);
        if (flushBatchSize > queueCapacity) {
            throw new IllegalArgumentException(
                    "rag.integration-observability.flush-batch-size "
                            + "must not exceed queue-capacity");
        }
        requireDurationRange(
                "rag.integration-observability.flush-interval",
                flushInterval,
                Duration.ofMillis(100),
                Duration.ofSeconds(60));
        requireDurationRange(
                "rag.integration-observability.shutdown-drain-timeout",
                shutdownDrainTimeout,
                Duration.ZERO,
                Duration.ofSeconds(30));
        requireRange(
                "rag.integration-observability.cleanup-batch-size",
                cleanupBatchSize,
                100,
                50_000);
        requireDurationRange(
                "rag.integration-observability.cleanup-interval",
                cleanupInterval,
                Duration.ofMinutes(1),
                Duration.ofHours(24));
    }

    private static void requireRange(
            String name,
            int value,
            int minimum,
            int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireDurationRange(
            String name,
            Duration value,
            Duration minimum,
            Duration maximum) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireWholeDays(String name, Duration value) {
        if (!value.minusDays(value.toDays()).isZero()) {
            throw new IllegalArgumentException(name + " must use whole days");
        }
    }
}
