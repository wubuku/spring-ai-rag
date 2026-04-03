package com.springairag.core.config;

/**
 * 分布式追踪配置
 *
 * <p>配置示例：
 * <pre>
 * rag:
 *   tracing:
 *     enabled: true
 *     sampling-rate: 1.0           # 0.0~1.0，1.0=全量追踪
 *     w3c-format: true             # 输出 W3C traceparent 格式
 *     span-id-enabled: true        # 生成 spanId 支持嵌套追踪
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
