package com.springairag.core.admission;

import com.springairag.core.config.RagAdmissionProperties;
import com.springairag.core.config.RagProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AdmissionService {
    private final RagAdmissionProperties properties;
    private final MeterRegistry metrics;
    private final Map<String, Pool> pools = Map.of(
            "request", new Pool(), "model", new Pool(), "background", new Pool(),
            "embedding", new Pool(), "backgroundEmbedding", new Pool(), "rerank", new Pool());

    public AdmissionService(RagProperties properties, MeterRegistry metrics) {
        this.properties = properties.getAdmission();
        this.metrics = metrics;
        pools.forEach((kind, pool) -> metrics.gauge("rag.admission.active",
                io.micrometer.core.instrument.Tags.of("kind", kind), pool, value -> active(kind)));
    }

    public Lease request(String principal) {
        return acquire("request", principal, "", properties.getMaxRequests(),
                properties.getMaxRequestsPerPrincipal(), Integer.MAX_VALUE);
    }

    public Lease model(String principal, String modelRef, boolean background) {
        if (background) {
            return acquire("background", principal, "", properties.getMaxBackgroundModelCalls(),
                    properties.getMaxBackgroundModelCalls(), Integer.MAX_VALUE);
        }
        String provider = modelRef == null ? "UNKNOWN" : modelRef.split("/", 2)[0];
        return acquire("model", principal, provider, properties.getMaxModelCalls(),
                properties.getMaxModelCallsPerPrincipal(), properties.getMaxModelCallsPerProvider());
    }

    public Lease dependency(String kind) {
        int limit = switch (kind) {
            case "embedding" -> properties.getMaxEmbeddingCalls();
            case "backgroundEmbedding" -> properties.getMaxBackgroundEmbeddingCalls();
            case "rerank" -> properties.getMaxRerankCalls();
            default -> throw new IllegalArgumentException("Unknown dependency kind");
        };
        return acquire(kind, "dependency", "", limit, limit, limit);
    }

    private synchronized Lease acquire(String kind, String principal, String provider,
            int totalLimit, int principalLimit, int providerLimit) {
        if (!properties.isEnabled()) {
            return new Lease(() -> { });
        }
        Pool pool = pools.get(kind);
        if (pool.total >= totalLimit || pool.principals.getOrDefault(principal, 0) >= principalLimit
                || pool.providers.getOrDefault(provider, 0) >= providerLimit) {
            metrics.counter("rag.admission.rejected", "kind", kind).increment();
            throw new AdmissionRejectedException();
        }
        pool.total++;
        pool.principals.merge(principal, 1, Integer::sum);
        pool.providers.merge(provider, 1, Integer::sum);
        return new Lease(() -> release(pool, principal, provider));
    }

    private synchronized void release(Pool pool, String principal, String provider) {
        pool.total--;
        decrement(pool.principals, principal);
        decrement(pool.providers, provider);
    }

    private void decrement(Map<String, Integer> counts, String key) {
        counts.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
    }

    public synchronized int active(String kind) { return pools.get(kind).total; }
    public synchronized int principalCount(String kind) { return pools.get(kind).principals.size(); }

    private static final class Pool {
        private int total;
        private final Map<String, Integer> principals = new HashMap<>();
        private final Map<String, Integer> providers = new HashMap<>();
    }

    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable release;

        private Lease(Runnable release) { this.release = release; }
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
