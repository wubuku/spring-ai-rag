package com.springairag.core.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Set;

/** 仅使用固定枚举标签的限流观测入口。 */
public final class RateLimitObservability {

    private static final Set<String> BACKENDS = Set.of("local", "postgresql");
    private static final Set<String> RESULTS = Set.of("allowed", "rejected", "error");
    private static final Set<String> PRINCIPAL_TYPES = Set.of(
            "DATABASE_API_KEY", "ENVIRONMENT_ROOT", "LEGACY_STATIC",
            "api-key", "user", "ip", "UNKNOWN");

    private final MeterRegistry registry;

    public RateLimitObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public static RateLimitObservability noop() {
        return new RateLimitObservability(null);
    }

    public void recordDecision(String backend, String result, String principalType) {
        if (registry == null) {
            return;
        }
        Counter.builder("rag.rate_limit.decisions")
                .tag("backend", fixed(backend, BACKENDS))
                .tag("result", fixed(result, RESULTS))
                .tag("principal_type", fixed(principalType, PRINCIPAL_TYPES))
                .register(registry)
                .increment();
    }

    public void recordCleanupError() {
        if (registry == null) {
            return;
        }
        Counter.builder("rag.rate_limit.cleanup.errors")
                .tag("backend", "postgresql")
                .register(registry)
                .increment();
    }

    private String fixed(String value, Set<String> allowed) {
        return allowed.contains(value) ? value : "UNKNOWN";
    }
}
