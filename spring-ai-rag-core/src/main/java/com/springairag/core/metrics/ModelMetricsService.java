package com.springairag.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Model-level metrics service.
 *
 * <p>Tracks per-provider call volume, latency, and error rates.
 */
@Component
public class ModelMetricsService {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> callCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    public ModelMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Record a successful model call.
     */
    public void recordSuccess(String provider, long durationMs) {
        getOrCreateCallCounter(provider).increment();
        getOrCreateLatencyTimer(provider).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record a failed model call.
     */
    public void recordError(String provider, long durationMs) {
        getOrCreateCallCounter(provider).increment();
        getOrCreateErrorCounter(provider).increment();
        getOrCreateLatencyTimer(provider).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Gets the call count for the specified provider.
     */
    public long getCallCount(String provider) {
        Counter c = callCounters.get(provider);
        return c != null ? (long) c.count() : 0;
    }

    /**
     * Gets the error count for the specified provider.
     */
    public long getErrorCount(String provider) {
        Counter c = errorCounters.get(provider);
        return c != null ? (long) c.count() : 0;
    }

    /**
     * Gets the error rate for the specified provider.
     */
    public double getErrorRate(String provider) {
        long total = getCallCount(provider);
        if (total == 0) return 0.0;
        return (double) getErrorCount(provider) / total;
    }

    private Counter getOrCreateCallCounter(String provider) {
        return callCounters.computeIfAbsent(provider, p ->
                Counter.builder("rag.model.calls.total")
                        .description("Total model calls per provider")
                        .tag("provider", p)
                        .register(meterRegistry));
    }

    private Counter getOrCreateErrorCounter(String provider) {
        return errorCounters.computeIfAbsent(provider, p ->
                Counter.builder("rag.model.errors.total")
                        .description("Total model errors per provider")
                        .tag("provider", p)
                        .register(meterRegistry));
    }

    private Timer getOrCreateLatencyTimer(String provider) {
        return latencyTimers.computeIfAbsent(provider, p ->
                Timer.builder("rag.model.latency")
                        .description("Model response latency per provider")
                        .tag("provider", p)
                        .register(meterRegistry));
    }
}
