package com.springairag.core.config;

/**
 * Distributed Tracing Configuration
 *
 * <p>Configuration example:
 * <pre>
 * rag:
 *   tracing:
 *     enabled: true
 *     sampling-rate: 1.0           # 0.0~1.0, 1.0=full tracing
 *     w3c-format: true             # Output W3C traceparent format
 *     span-id-enabled: true        # Generate spanId for nested tracing
 * </pre>
 */
public class RagTracingProperties {

    private boolean enabled = true;
    private double samplingRate = 1.0;
    private boolean w3cFormat = false;
    private boolean spanIdEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getSamplingRate() {
        return samplingRate;
    }

    public void setSamplingRate(double samplingRate) {
        this.samplingRate = samplingRate;
    }

    public boolean isW3cFormat() {
        return w3cFormat;
    }

    public void setW3cFormat(boolean w3cFormat) {
        this.w3cFormat = w3cFormat;
    }

    public boolean isSpanIdEnabled() {
        return spanIdEnabled;
    }

    public void setSpanIdEnabled(boolean spanIdEnabled) {
        this.spanIdEnabled = spanIdEnabled;
    }
}
