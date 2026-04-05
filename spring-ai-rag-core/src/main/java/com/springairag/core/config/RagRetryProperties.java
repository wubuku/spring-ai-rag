package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Retry configuration for LLM API calls.
 *
 * <p>Provides declarative retry with exponential backoff for transient failures
 * such as network timeouts, rate limits (429), and temporary server errors (503).
 *
 * <p>Retry is applied to:
 * <ul>
 *   <li>Chat completion calls (non-streaming)</li>
 *   <li>Query rewriting (LLM-based mode)</li>
 * </ul>
 *
 * <p>Note: Streaming (SSE) responses cannot be retried due to the reactive nature.
 *
 * <p>Configuration prefix: {@code rag.retry}
 */
@Validated
@ConfigurationProperties(prefix = "rag.retry")
public class RagRetryProperties {

    /**
     * Whether retry is enabled globally.
     */
    private boolean enabled = true;

    /**
     * Maximum number of retry attempts (including the initial call).
     * Set to 1 to effectively disable retry.
     */
    @Min(1)
    @Max(5)
    private int maxAttempts = 3;

    /**
     * Initial backoff delay in milliseconds.
     * Uses exponential backoff: delay = initialBackoffMs * 2^(attempt-1).
     */
    @Min(100)
    private long initialBackoffMs = 1_000;

    /**
     * Maximum backoff delay in milliseconds.
     * Prevents exponential backoff from growing unbounded.
     */
    @Min(1000)
    private long maxBackoffMs = 10_000;

    /**
     * Multiplier for exponential backoff. Each retry uses:
     * min(initialBackoffMs * multiplier^(attempt-1), maxBackoffMs).
     */
    @DecimalMin("1.0")
    private double backoffMultiplier = 2.0;

    /**
     * Whether to retry on HTTP 429 Too Many Requests (rate limit).
     * When enabled, respects Retry-After header if present.
     */
    private boolean retryOnRateLimit = true;

    /**
     * Whether to retry on HTTP 503 Service Unavailable.
     */
    private boolean retryOnServiceUnavailable = true;

    /**
     * Whether to retry on connect timeouts.
     */
    private boolean retryOnConnectTimeout = true;

    /**
     * Whether to retry on read timeouts.
     */
    private boolean retryOnReadTimeout = true;

    /**
     * Retry jitter factor (0.0 to 1.0). Adds random variation to backoff
     * to prevent thundering herd. 0.0 disables jitter.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double jitterFactor = 0.2;

    // --- Getters and Setters ---

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public boolean isRetryOnRateLimit() {
        return retryOnRateLimit;
    }

    public void setRetryOnRateLimit(boolean retryOnRateLimit) {
        this.retryOnRateLimit = retryOnRateLimit;
    }

    public boolean isRetryOnServiceUnavailable() {
        return retryOnServiceUnavailable;
    }

    public void setRetryOnServiceUnavailable(boolean retryOnServiceUnavailable) {
        this.retryOnServiceUnavailable = retryOnServiceUnavailable;
    }

    public boolean isRetryOnConnectTimeout() {
        return retryOnConnectTimeout;
    }

    public void setRetryOnConnectTimeout(boolean retryOnConnectTimeout) {
        this.retryOnConnectTimeout = retryOnConnectTimeout;
    }

    public boolean isRetryOnReadTimeout() {
        return retryOnReadTimeout;
    }

    public void setRetryOnReadTimeout(boolean retryOnReadTimeout) {
        this.retryOnReadTimeout = retryOnReadTimeout;
    }

    public double getJitterFactor() {
        return jitterFactor;
    }

    public void setJitterFactor(double jitterFactor) {
        this.jitterFactor = jitterFactor;
    }
}
