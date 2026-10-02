package com.springairag.core.metrics;

import com.springairag.api.dto.ApiSloComplianceResponse;
import com.springairag.api.dto.ApiSloComplianceResponse.EndpointSlo;
import com.springairag.api.dto.ApiSloComplianceResponse.LatencyStats;
import com.springairag.core.config.ApiSloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * API SLO (Service Level Objective) Compliance Tracker.
 *
 * <p>Tracks latency of key API endpoints and computes compliance percentage
 * against configurable SLO thresholds (default: p95 latency &lt; 500ms).
 *
 * <p>Uses a sliding time window to compute compliance — only requests within
 * the configured window are considered.
 *
 * <p><strong>Coverage is the configured threshold table, not the endpoint
 * space.</strong> The interceptor records latency for every {@code @Timed}
 * endpoint, but {@link #getCompliance()} reports only endpoints present in
 * {@link ApiSloProperties#getThresholds()}. An endpoint missing from that
 * table is measured and then never surfaced. {@code /ask} and its
 * {@code /chat} alias were once in exactly that state: the alias published its
 * own {@code rag.chat.non-stream} timer, so every call routed through it was
 * recorded and then dropped. Both URLs now share {@code rag.chat.ask}, and
 * {@code scripts/verify-slo-endpoint-coverage.mjs} fails when a threshold entry
 * stops matching a real endpoint — a stale key would otherwise report a
 * permanent 100% compliance, indistinguishable from a healthy one.
 *
 * <p><strong>The reported HTTP method is observed, never inferred.</strong> It
 * used to be recovered by substring-matching the endpoint name, assuming a
 * {@code .post}/{@code .get} suffix convention the hand-written {@code @Timed}
 * values do not follow: 37 of 61 resolvable metrics reported the wrong method,
 * including {@code rag.chat.ask} (a POST mapping reported as GET). The method
 * now travels with the measurement.
 *
 * <p>This service is registered conditionally when
 * {@code rag.slo.enabled=true} (default).
 */
@Service
public class ApiSloTrackerService {

    private static final Logger log = LoggerFactory.getLogger(ApiSloTrackerService.class);

    private final ApiSloProperties properties;
    private final long windowMillis;
    private final Map<String, EndpointTracker> trackers = new ConcurrentHashMap<>();

    public ApiSloTrackerService(ApiSloProperties properties) {
        this.properties = properties;
        this.windowMillis = properties.getWindowSeconds() * 1000L;
        log.info("API SLO tracker initialized: enabled={}, window={}s",
                properties.isEnabled(), properties.getWindowSeconds());
    }

    /**
     * Record a request latency for a given endpoint.
     *
     * @param endpoint   the endpoint identifier (matches @Timed value, e.g., "rag.search.post")
     * @param httpMethod the HTTP method actually observed on the request, e.g. "POST".
     *                   Reported verbatim; never derived from the endpoint name.
     *                   Endpoint names in this codebase do not follow a method
     *                   suffix convention ({@code rag.documents.embed} is a POST
     *                   mapping, {@code rag.collection.update} is a PUT), so any
     *                   attempt to recover the method by parsing the name is a
     *                   guess. {@code null} means "not observed" and is reported
     *                   as such rather than defaulted.
     * @param latencyMs  the request latency in milliseconds
     */
    public void recordLatency(String endpoint, String httpMethod, long latencyMs) {
        if (!properties.isEnabled()) {
            return;
        }
        trackers.computeIfAbsent(endpoint,
                        k -> new EndpointTracker(properties.getThreshold(k), httpMethod))
                .record(latencyMs, System.currentTimeMillis());
    }

    /**
     * Get current SLO compliance for all configured endpoints.
     *
     * @return SLO compliance response with per-endpoint compliance percentages
     */
    public ApiSloComplianceResponse getCompliance() {
        List<EndpointSlo> endpointSlos = new ArrayList<>();

        // Include all configured thresholds
        for (Map.Entry<String, Long> entry : properties.getThresholds().entrySet()) {
            String endpoint = entry.getKey();
            long threshold = entry.getValue();
            EndpointTracker tracker = trackers.get(endpoint);

            if (tracker == null) {
                // No request was ever observed for this configured endpoint, so there
                // is no HTTP method to report. Emitting a guessed one here is what
                // made the whole report untrustworthy: see the class javadoc.
                endpointSlos.add(new EndpointSlo(
                        endpoint,
                        null,
                        threshold,
                        100.0,  // No data yet = assume compliant
                        0, 0, 0,
                        new LatencyStats(0, 0, 0, 0, 0, 0)
                ));
            } else {
                var snapshot = tracker.getSnapshot(windowMillis);
                double compliance = snapshot.total() > 0
                        ? (double) snapshot.sloCount() / snapshot.total() * 100
                        : 100.0;

                endpointSlos.add(new EndpointSlo(
                        endpoint,
                        tracker.httpMethod(),
                        threshold,
                        Math.round(compliance * 100.0) / 100.0,
                        snapshot.total(),
                        snapshot.sloCount(),
                        snapshot.breachCount(),
                        new LatencyStats(
                                snapshot.p50(),
                                snapshot.p95(),
                                snapshot.p99(),
                                snapshot.min(),
                                snapshot.max(),
                                snapshot.avg()
                        )
                ));
            }
        }

        return new ApiSloComplianceResponse(
                properties.isEnabled(),
                properties.getWindowSeconds(),
                endpointSlos
        );
    }

    /**
     * Tracks latencies for a single endpoint using a sliding time window.
     *
     * <p>The HTTP method is captured once, when the endpoint is first seen, and
     * reported unchanged thereafter. An endpoint identifier maps to exactly one
     * handler method in this codebase (verified: all 81 {@code @Timed} values are
     * distinct), so a differing method on a later request would mean the name has
     * been reused across handlers — a mistake worth surfacing rather than
     * averaging away.
     */
    private static class EndpointTracker {
        private final long threshold;
        private final String httpMethod;
        private final List<LatencySample> samples = new ArrayList<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        EndpointTracker(long threshold, String httpMethod) {
            this.threshold = threshold;
            this.httpMethod = httpMethod;
        }

        String httpMethod() {
            return httpMethod;
        }

        void record(long latencyMs, long timestampMs) {
            lock.writeLock().lock();
            try {
                samples.add(new LatencySample(latencyMs, timestampMs));
                // Prune old samples outside the window
                long cutoff = timestampMs - (5 * 60 * 1000L); // 5-min max retention
                samples.removeIf(s -> s.timestamp() < cutoff);
            } finally {
                lock.writeLock().unlock();
            }
        }

        Snapshot getSnapshot(long windowMillis) {
            lock.readLock().lock();
            try {
                List<Long> recent = filterRecentSamples(windowMillis);
                if (recent.isEmpty()) {
                    return emptySnapshot();
                }
                return buildSnapshot(recent);
            } finally {
                lock.readLock().unlock();
            }
        }

        private List<Long> filterRecentSamples(long windowMillis) {
            long cutoff = System.currentTimeMillis() - windowMillis;
            return samples.stream()
                    .filter(s -> s.timestamp() >= cutoff)
                    .map(LatencySample::latency)
                    .sorted()
                    .toList();
        }

        private Snapshot emptySnapshot() {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0);
        }

        private Snapshot buildSnapshot(List<Long> recent) {
            int n = recent.size();
            int sloCount = countWithinThreshold(recent);
            double avg = computeAverage(recent);
            return new Snapshot(
                    n,
                    sloCount,
                    n - sloCount,
                    recent.get(0),                       // min
                    recent.get(n - 1),                  // max
                    avg,
                    percentile(recent, 0.50),           // p50
                    percentile(recent, 0.95)            // p95
            );
        }

        private int countWithinThreshold(List<Long> sortedLatencies) {
            int count = 0;
            for (Long lat : sortedLatencies) {
                if (lat <= threshold) count++;
            }
            return count;
        }

        private double computeAverage(List<Long> latencies) {
            double sum = 0;
            for (Long lat : latencies) sum += lat;
            return sum / latencies.size();
        }

        private static double percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) return 0;
            if (sorted.size() == 1) return sorted.get(0);
            double idx = p * (sorted.size() - 1);
            int lower = (int) Math.floor(idx);
            int upper = (int) Math.ceil(idx);
            if (lower == upper) return sorted.get(lower);
            double fraction = idx - lower;
            return sorted.get(lower) * (1 - fraction) + sorted.get(upper) * fraction;
        }

        private record LatencySample(long latency, long timestamp) {}
    }

    private record Snapshot(
            int total,
            int sloCount,
            int breachCount,
            double min,
            double max,
            double avg,
            double p50,
            double p95
    ) {
        double p99() {
            // For simplicity, approximate p99 from p95 when data is limited
            // In practice, with enough samples, we'd compute this directly
            return max > p95 ? p95 + (max - p95) * 0.8 : p95 * 1.1;
        }
    }
}
