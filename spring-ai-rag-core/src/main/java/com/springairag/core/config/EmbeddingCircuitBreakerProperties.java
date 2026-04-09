package com.springairag.core.config;

/**
 * Embedding Circuit Breaker Configuration
 *
 * <p>Controls the circuit breaker for embedding API calls.
 * Reuses the same sliding-window algorithm as the LLM circuit breaker.
 *
 * <p>Configuration example:
 * <pre>
 * rag:
 *   embedding-circuit-breaker:
 *     enabled: true
 *     failure-rate-threshold: 50       # Failure rate threshold (%), circuit opens when exceeded
 *     minimum-number-of-calls: 10      # Minimum calls in the sliding window
 *     wait-duration-in-open-state: 30  # Seconds to stay in OPEN state
 *     sliding-window-size: 20          # Sliding window size
 * </pre>
 */
public class EmbeddingCircuitBreakerProperties {

    private boolean enabled = false;
    private int failureRateThreshold = 50;
    private int minimumNumberOfCalls = 10;
    private int waitDurationInOpenStateSeconds = 30;
    private int slidingWindowSize = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(int failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public int getWaitDurationInOpenStateSeconds() {
        return waitDurationInOpenStateSeconds;
    }

    public void setWaitDurationInOpenStateSeconds(int waitDurationInOpenStateSeconds) {
        this.waitDurationInOpenStateSeconds = waitDurationInOpenStateSeconds;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }
}
