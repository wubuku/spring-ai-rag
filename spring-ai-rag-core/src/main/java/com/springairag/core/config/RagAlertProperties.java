package com.springairag.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for alerting thresholds and SLO (Service Level Objective) definitions.
 *
 * <p>Externalizes the alert threshold constants that were previously hardcoded in
 * {@code AlertServiceImpl}, allowing operators to adjust alert thresholds without
 * recompiling the application.
 *
 * <p>Example configuration in application.yml:
 * <pre>
 * rag:
 *   alert:
 *     availability-slo: 99.9
 *     latency-p50-slo-ms: 500
 *     latency-p95-slo-ms: 2000
 *     latency-p99-slo-ms: 5000
 *     mrr-slo: 0.6
 *     hit-rate-slo: 0.85
 * </pre>
 */
@ConfigurationProperties(prefix = "rag.alert")
public class RagAlertProperties {

    /**
     * Availability SLO threshold (percentage).
     * Default: 99.9%
     */
    private double availabilitySlo = 99.9;

    /**
     * P50 latency SLO threshold (milliseconds).
     * Default: 500ms
     */
    private double latencyP50SloMs = 500;

    /**
     * P95 latency SLO threshold (milliseconds).
     * Default: 2000ms
     */
    private double latencyP95SloMs = 2000;

    /**
     * P99 latency SLO threshold (milliseconds).
     * Default: 5000ms
     */
    private double latencyP99SloMs = 5000;

    /**
     * Mean Reciprocal Rank (MRR) quality SLO threshold.
     * Default: 0.6
     */
    private double mrrSlo = 0.6;

    /**
     * Embedding cache hit rate SLO threshold.
     * Default: 0.85
     */
    private double hitRateSlo = 0.85;

    public double getAvailabilitySlo() {
        return availabilitySlo;
    }

    public void setAvailabilitySlo(double availabilitySlo) {
        this.availabilitySlo = availabilitySlo;
    }

    public double getLatencyP50SloMs() {
        return latencyP50SloMs;
    }

    public void setLatencyP50SloMs(double latencyP50SloMs) {
        this.latencyP50SloMs = latencyP50SloMs;
    }

    public double getLatencyP95SloMs() {
        return latencyP95SloMs;
    }

    public void setLatencyP95SloMs(double latencyP95SloMs) {
        this.latencyP95SloMs = latencyP95SloMs;
    }

    public double getLatencyP99SloMs() {
        return latencyP99SloMs;
    }

    public void setLatencyP99SloMs(double latencyP99SloMs) {
        this.latencyP99SloMs = latencyP99SloMs;
    }

    public double getMrrSlo() {
        return mrrSlo;
    }

    public void setMrrSlo(double mrrSlo) {
        this.mrrSlo = mrrSlo;
    }

    public double getHitRateSlo() {
        return hitRateSlo;
    }

    public void setHitRateSlo(double hitRateSlo) {
        this.hitRateSlo = hitRateSlo;
    }
}
