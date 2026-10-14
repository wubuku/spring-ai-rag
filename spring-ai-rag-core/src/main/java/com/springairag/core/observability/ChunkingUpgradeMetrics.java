package com.springairag.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class ChunkingUpgradeMetrics {
    private static final Set<String> STATUSES = Set.of("RUNNING", "RETRY", "COOLDOWN", "FAILED", "COMPLETED");
    private static final Set<String> KINDS = Set.of("NONE", "TRANSIENT", "REJECTED", "UNKNOWN", "CONFLICT", "PERMANENT");
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> counts = new LinkedHashMap<>();
    private final AtomicLong lastScanNanos = new AtomicLong();

    public ChunkingUpgradeMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String status : Set.of("RUNNING", "RETRY", "COOLDOWN", "FAILED")) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder("rag.chunking.upgrade.tasks", count, AtomicLong::get)
                    .tag("status", status).register(registry);
        }
        Gauge.builder("rag.chunking.upgrade.scan.age.seconds", lastScanNanos,
                value -> value.get() == 0 ? Double.NaN : (System.nanoTime() - value.get()) / 1_000_000_000.0)
                .register(registry);
    }

    public void scanned(Map<String, Long> snapshot) {
        counts.forEach((status, count) -> count.set(Math.max(0, snapshot.getOrDefault(status, 0L))));
        lastScanNanos.set(System.nanoTime());
    }

    public void transitioned(String status, String kind) {
        Counter.builder("rag.chunking.upgrade.transitions")
                .tag("status", STATUSES.contains(status) ? status : "FAILED")
                .tag("failure_kind", KINDS.contains(kind) ? kind : "UNKNOWN")
                .register(registry).increment();
    }
}
