package com.springairag.core.config;

import java.time.Duration;

/** 受管 API principal 到期预警的窗口、扫描与竞争重试边界。 */
public class RagApiKeyExpiryAlertProperties {

    private boolean enabled = true;
    private Duration warningWindow = Duration.ofDays(30);
    private Duration criticalWindow = Duration.ofDays(7);
    private Duration fallbackScanInterval = Duration.ofHours(1);
    private int fallbackScanLimit = 10_000;
    private int eventRetryAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getWarningWindow() { return warningWindow; }
    public void setWarningWindow(Duration warningWindow) {
        this.warningWindow = warningWindow;
    }
    public Duration getCriticalWindow() { return criticalWindow; }
    public void setCriticalWindow(Duration criticalWindow) {
        this.criticalWindow = criticalWindow;
    }
    public Duration getFallbackScanInterval() { return fallbackScanInterval; }
    public void setFallbackScanInterval(Duration fallbackScanInterval) {
        this.fallbackScanInterval = fallbackScanInterval;
    }
    public int getFallbackScanLimit() { return fallbackScanLimit; }
    public void setFallbackScanLimit(int fallbackScanLimit) {
        this.fallbackScanLimit = fallbackScanLimit;
    }
    public int getEventRetryAttempts() { return eventRetryAttempts; }
    public void setEventRetryAttempts(int eventRetryAttempts) {
        this.eventRetryAttempts = eventRetryAttempts;
    }

    public void validate() {
        duration("warning-window", warningWindow,
                Duration.ofDays(1), Duration.ofDays(180));
        duration("critical-window", criticalWindow,
                Duration.ofHours(1), warningWindow.minusNanos(1));
        duration("fallback-scan-interval", fallbackScanInterval,
                Duration.ofMinutes(10), Duration.ofHours(24));
        range("fallback-scan-limit", fallbackScanLimit, 100, 100_000);
        range("event-retry-attempts", eventRetryAttempts, 1, 10);
    }

    private static void duration(
            String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || maximum.isNegative()
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "rag.api-key-expiry-alerts." + name + " must be between "
                            + minimum + " and " + maximum);
        }
    }

    private static void range(
            String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "rag.api-key-expiry-alerts." + name + " must be between "
                            + minimum + " and " + maximum);
        }
    }
}
