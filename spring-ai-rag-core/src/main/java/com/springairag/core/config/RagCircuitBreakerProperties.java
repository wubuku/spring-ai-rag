package com.springairag.core.config;

/**
 * LLM 熔断器配置
 *
 * <p>配置示例：
 * <pre>
 * rag:
 *   circuit-breaker:
 *     enabled: true
 *     failure-rate-threshold: 50       # 失败率阈值（%），超过则熔断
 *     minimum-number-of-calls: 10      # 滑动窗口内最小调用数
 *     wait-duration-in-open-state: 30  # OPEN 状态持续秒数
 *     sliding-window-size: 20          # 滑动窗口大小
 * </pre>
 */
public class RagCircuitBreakerProperties {

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
