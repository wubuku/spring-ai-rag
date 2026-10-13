package com.springairag.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** 文档处理账本的低基数 Micrometer 指标。 */
@Component
public final class DocumentProcessingMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeRuns = new AtomicInteger();

    public DocumentProcessingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("rag.document-processing.active", activeRuns,
                        AtomicInteger::get)
                .description("Active document processing runs")
                .register(meterRegistry);
    }

    public void setActiveRuns(int count) {
        activeRuns.set(Math.max(0, count));
    }

    public void recordRunTerminal(String status, String origin,
            String terminalReason) {
        Counter.builder("rag.document-processing.runs")
                .description("Document processing run terminal transitions")
                .tag("status", finite(status, "UNKNOWN"))
                .tag("origin", finite(origin, "UNKNOWN"))
                .tag("terminal_reason", finite(terminalReason, "NONE"))
                .register(meterRegistry)
                .increment();
    }

    public void recordStageDuration(String stage, String result,
            Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        Timer.builder("rag.document-processing.stage.duration")
                .description("Document processing stage duration")
                .tag("stage", finite(stage, "UNKNOWN"))
                .tag("result", finite(result, "UNKNOWN"))
                .register(meterRegistry)
                .record(duration);
    }

    public void recordHousekeeping(String action) {
        Counter.builder("rag.document-processing.housekeeping.reconciled")
                .description("Document processing housekeeping actions")
                .tag("action", finite(action, "unknown"))
                .register(meterRegistry)
                .increment();
    }

    public void recordStuck(String stage) {
        Counter.builder("rag.document-processing.stuck")
                .description("Stuck document processing runs")
                .tag("stage", finite(stage, "UNKNOWN"))
                .register(meterRegistry)
                .increment();
    }

    private static String finite(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
