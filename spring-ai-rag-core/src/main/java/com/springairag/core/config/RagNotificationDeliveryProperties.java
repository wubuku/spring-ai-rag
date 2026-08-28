package com.springairag.core.config;

import java.time.Duration;

/** 持久化告警通知投递的 worker、重试与保留配置。 */
public class RagNotificationDeliveryProperties {

    private boolean enabled;
    private Duration fallbackScanInterval = Duration.ofMinutes(1);
    private int workerConcurrency = 4;
    private int claimBatchSize = 100;
    private Duration providerAttemptTimeout = Duration.ofSeconds(30);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private int maxAttempts = 8;
    private Duration initialBackoff = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofHours(1);
    private Duration deliveredRetention = Duration.ofDays(30);
    private Duration failedRetention = Duration.ofDays(90);
    private Duration cleanupInterval = Duration.ofHours(1);
    private int cleanupBatchSize = 1000;
    private int maxPayloadBytes = 65_536;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getFallbackScanInterval() { return fallbackScanInterval; }
    public void setFallbackScanInterval(Duration value) { fallbackScanInterval = value; }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int value) { workerConcurrency = value; }
    public int getClaimBatchSize() { return claimBatchSize; }
    public void setClaimBatchSize(int value) { claimBatchSize = value; }
    public Duration getProviderAttemptTimeout() { return providerAttemptTimeout; }
    public void setProviderAttemptTimeout(Duration value) { providerAttemptTimeout = value; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration value) { initialBackoff = value; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration value) { maxBackoff = value; }
    public Duration getDeliveredRetention() { return deliveredRetention; }
    public void setDeliveredRetention(Duration value) { deliveredRetention = value; }
    public Duration getFailedRetention() { return failedRetention; }
    public void setFailedRetention(Duration value) { failedRetention = value; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration value) { cleanupInterval = value; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int value) { cleanupBatchSize = value; }
    public int getMaxPayloadBytes() { return maxPayloadBytes; }
    public void setMaxPayloadBytes(int value) { maxPayloadBytes = value; }

    public void validate() {
        duration("fallback-scan-interval", fallbackScanInterval,
                Duration.ofSeconds(10), Duration.ofMinutes(10));
        range("worker-concurrency", workerConcurrency, 1, 32);
        range("claim-batch-size", claimBatchSize, 1, 1000);
        if (claimBatchSize < workerConcurrency) {
            throw invalid("claim-batch-size must not be smaller than worker-concurrency");
        }
        duration("provider-attempt-timeout", providerAttemptTimeout,
                Duration.ofSeconds(5), Duration.ofSeconds(60));
        duration("lease-duration", leaseDuration,
                Duration.ofSeconds(30), Duration.ofMinutes(15));
        if (leaseDuration.compareTo(providerAttemptTimeout.multipliedBy(2)) < 0) {
            throw invalid("lease-duration must be at least twice provider-attempt-timeout");
        }
        range("max-attempts", maxAttempts, 1, 20);
        duration("initial-backoff", initialBackoff,
                Duration.ofSeconds(1), Duration.ofMinutes(10));
        duration("max-backoff", maxBackoff, initialBackoff, Duration.ofHours(24));
        duration("delivered-retention", deliveredRetention,
                Duration.ofDays(1), Duration.ofDays(365));
        duration("failed-retention", failedRetention,
                Duration.ofDays(7), Duration.ofDays(730));
        duration("cleanup-interval", cleanupInterval,
                Duration.ofMinutes(10), Duration.ofHours(24));
        range("cleanup-batch-size", cleanupBatchSize, 100, 10_000);
        range("max-payload-bytes", maxPayloadBytes, 4096, 1_048_576);
    }

    private static void duration(
            String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw invalid(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw invalid(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("rag.notifications.delivery." + detail);
    }
}
